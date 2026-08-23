package com.piercingxx.xxnote.net

import com.piercingxx.xxnote.sync.PutResult
import com.piercingxx.xxnote.sync.RemoteEntry
import com.piercingxx.xxnote.sync.RemoteFiles
import java.io.IOException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The far side ([RemoteFiles]): WebDAV over HTTPS to exactly one Synology
 * origin (D4), guarded by [OneHostInterceptor] (R8).
 *
 * **Blocking by design.** Every method performs a synchronous network call
 * and must run off the main thread — the sync engine executes on WorkManager
 * worker threads and never blocks a UI frame on I/O.
 *
 * Redirects are never chased (`followRedirects(false)` +
 * `followSslRedirects(false)`): following one would mean leaving the one
 * host, which R8 forbids outright. A 3xx therefore surfaces to the caller as
 * a failure (an [IOException] naming the status, or [PutResult.FAILED]) with
 * zero bytes sent anywhere else.
 *
 * Failure surface:
 * - `list`/`get` throw [HttpError] (an [IOException]) on any non-success
 *   status (the message carries the HTTP code verbatim for §15's "surfaced on
 *   the sync screen"; it NEVER carries credentials). Only `get` maps 404 →
 *   null.
 * - `put`/`putIfAbsent`/`move`/`delete`/`mkcol` map statuses to their return
 *   values instead of throwing; transport errors count as FAILED/false.
 *
 * No request or error is ever logged, and nothing here touches the
 * credential beyond the Authorization header OkHttp sends.
 */
/**
 * A non-success HTTP status surfaced as an exception (R10): [status] lets
 * callers distinguish permanent refusals — 401/403 mean stale credentials and
 * must never enter a retry loop — from transient trouble, while [message]
 * keeps the status verbatim for the sync screen. Never carries credentials.
 */
class HttpError(val status: Int, message: String) : IOException(message)

class WebDavClient(
    host: String,
    port: Int,
    basePath: String,
    username: String,
    password: String,
    /**
     * Production callers never pass this: HTTPS-only (§4.1, R8 — the
     * network security config forbids cleartext regardless). The parameter
     * exists so JVM tests can point the client at plain-HTTP MockWebServer.
     */
    private val scheme: String = "https",
) : RemoteFiles {

    private val auth: String =
        Credentials.basic(username, password, Charsets.UTF_8)

    private val baseUrl: HttpUrl =
        buildOrigin(host, port, basePath, scheme)

    /** OkHttp client bound to this origin for life; not per-request. */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(OneHostInterceptor(host, port, scheme))
        .build()

    override fun list(dirPath: String): List<RemoteEntry> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:getetag/>
                <d:getcontentlength/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
        val request = Request.Builder()
            .url(urlFor(dirPath, collection = true))
            .header("Authorization", auth)
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody(XML))
            .build()
        return execute(request) { response ->
            if (response.code != 207) {
                throw HttpError(
                    response.code,
                    "PROPFIND ${response.request.url.redact()} failed: HTTP ${response.code}",
                )
            }
            PropfindParser.parse(checkNotNull(response.body).string())
        }
    }

    override fun get(filePath: String): String? {
        val request = baseRequest(filePath).get().build()
        return execute(request) { response ->
            when {
                response.isSuccessful -> checkNotNull(response.body).string()
                response.code == 404 -> null
                else -> throw HttpError(
                    response.code,
                    "GET ${response.request.url.redact()} failed: HTTP ${response.code}",
                )
            }
        }
    }

    override fun put(filePath: String, wholeFileText: String, ifMatch: String?): PutResult {
        val builder = putRequestBuilder(filePath, wholeFileText)
        ifMatch?.let { builder.header("If-Match", it) }
        return putOutcome(builder.build())
    }

    override fun putIfAbsent(filePath: String, wholeFileText: String): PutResult {
        val builder = putRequestBuilder(filePath, wholeFileText)
        builder.header("If-None-Match", "*")
        return putOutcome(builder.build())
    }

    // --- binary attachments (WS10, §10) — additive; same failure surface ---
    // Content-addressed attachment files are immutable (D13): no If-Match is
    // ever needed on their PUT — "exists or it doesn't" is the whole sync
    // question. [putFile] maps statuses exactly like [put]; [getFile] maps
    // them exactly like [get] (404 → null, everything else throws), except it
    // returns raw bytes instead of text.

    /**
     * Unconditional write of raw [bytes] at [relativePath]. Returns
     * [PutResult.WRITTEN] with the server's ETag, [PutResult.PRECONDITION_FAILED]
     * on 412 (not expected for immutable attachments), [PutResult.FAILED] on
     * any other HTTP status or transport error.
     */
    override fun putFile(relativePath: String, bytes: ByteArray): PutResult {
        val request = baseRequest(relativePath)
            .put(bytes.toRequestBody(BINARY))
            .build()
        return putOutcome(request)
    }

    /**
     * Raw bytes at [relativePath], or null on 404. Any other non-success
     * status throws [HttpError] (message carries the code verbatim, never
     * credentials); transport errors propagate as [IOException].
     */
    override fun getFile(relativePath: String): ByteArray? {
        val request = baseRequest(relativePath).get().build()
        return execute(request) { response ->
            when {
                response.isSuccessful -> checkNotNull(response.body).bytes()
                response.code == 404 -> null
                else -> throw HttpError(
                    response.code,
                    "GET ${response.request.url.redact()} failed: HTTP ${response.code}",
                )
            }
        }
    }

    override fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean {
        // Destination is the absolute URL of the target, per RFC 4918. The
        // Overwrite header is sent explicitly: `F` makes a trash move fail at
        // the server instead of silently clobbering an existing copy there.
        val request = baseRequest(fromPath)
            .header("Destination", urlFor(toPath).toString())
            .header("Overwrite", if (overwrite) "T" else "F")
            .method("MOVE", null)
            .build()
        return quiet(request, false) { it.isSuccessful }
    }

    override fun delete(filePath: String): Boolean {
        val request = baseRequest(filePath).delete().build()
        // 404 is success (already gone); a dead wire is NOT — the engine
        // must be able to tell "gone" from "couldn't ask" (R10).
        return quiet(request, false) { it.isSuccessful || it.code == 404 }
    }

    override fun mkcol(dirPath: String): Boolean {
        val request = baseRequest(dirPath, collection = true)
            .method("MKCOL", null)
            .build()
        // 405 means the collection already exists — success for our purposes
        // (idempotent folder skeleton).
        return quiet(request, false) { it.code == 201 || it.code == 405 }
    }

    // --- plumbing ---------------------------------------------------------

    private fun putRequestBuilder(filePath: String, wholeFileText: String): Request.Builder =
        baseRequest(filePath)
            .put(wholeFileText.toByteArray(Charsets.UTF_8).toRequestBody(MARKDOWN))

    private fun putOutcome(request: Request): PutResult =
        quiet(request, PutResult.FAILED) { response ->
            when {
                // OkHttp's header lookup is case-insensitive, so `ETag` and
                // `Etag` spellings both land here; kept verbatim (quoted).
                response.isSuccessful -> PutResult.WRITTEN(response.header("ETag"))
                response.code == 412 -> PutResult.PRECONDITION_FAILED
                else -> PutResult.FAILED
            }
        }

    private fun baseRequest(path: String, collection: Boolean = false): Request.Builder =
        Request.Builder()
            .url(urlFor(path, collection))
            .header("Authorization", auth)

    /**
     * Resolves a vault-relative path against the configured origin. Segments
     * are encoded individually, so spaces and CJK filenames arrive correctly;
     * collections get a trailing slash.
     */
    internal fun urlFor(path: String, collection: Boolean = false): HttpUrl {
        val builder = baseUrl.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        if (collection) builder.addPathSegment("")
        return builder.build()
    }

    private fun <T> execute(request: Request, onSuccess: (Response) -> T): T =
        client.newCall(request).execute().use(onSuccess)

    /**
     * Runs a call whose failures are values, not exceptions: a refused
     * connection or reset socket collapses onto [onTransportFailure].
     */
    private inline fun <T> quiet(
        request: Request,
        onTransportFailure: T,
        map: (Response) -> T,
    ): T =
        try {
            client.newCall(request).execute().use(map)
        } catch (_: IOException) {
            onTransportFailure
        }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val MARKDOWN = "text/markdown; charset=utf-8".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()

        private fun buildOrigin(
            host: String,
            port: Int,
            basePath: String,
            scheme: String,
        ): HttpUrl {
            val builder = HttpUrl.Builder()
                .scheme(scheme)
                .host(host)
                .port(port)
            basePath.split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
            return builder.build()
        }
    }
}
