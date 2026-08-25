# Android integration — xx-note ↔ xxnote-server (follow-up)

**Status:** documented, not built. No Android toolchain (gradle / JDK / SDK) is
present in the build environment used for the server, so the Kotlin below is
un-compiled. It is intentionally small because the app's sync already sits
behind the `RemoteFiles` port (`app/.../sync/Ports.kt`): the whole integration
is **one new `RemoteFiles` implementation + a login screen**, with zero change
to `SyncEngine`, `SyncPolicy`, `MergeEngine`, or `VaultStore`.

## 1. New `RemoteFiles` implementation — `net/FabricFilesClient.kt`

Mirrors `WebDavClient`'s contract exactly (`HttpError(status)` for `list`/`get`,
status→return mapping for the mutating methods, 404→null on `get`, never logs or
leaks the token). Swaps `Credentials.basic(...)` for `Authorization: Bearer
<token>` and the WebDAV verbs for xxnote-server's JSON API. `OneHostInterceptor`
is reused unchanged — its "one host" rule now names the fabric backend instead
of the Synology.

```kotlin
package com.piercingxx.xxnote.net

import com.piercingxx.xxnote.sync.PutResult
import com.piercingxx.xxnote.sync.RemoteEntry
import com.piercingxx.xxnote.sync.RemoteFiles
import java.io.IOException
import org.json.JSONObject
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

/** RemoteFiles against xxnote-server. One host (the fabric backend), Bearer auth. */
class FabricFilesClient(
    host: String,
    port: Int,
    private val token: String,
    scheme: String = "https",
) : RemoteFiles {

    private val base: HttpUrl = HttpUrl.Builder().scheme(scheme).host(host).port(port).build()
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor(OneHostInterceptor(host, port, scheme))
        .build()

    private fun req(b: Request.Builder) = b.header("Authorization", "Bearer $token").build()
    private fun fileUrl(path: String) = base.newBuilder()
        .addPathSegments("api/v1/file")
        .addPathSegments(path.trimStart('/')).build()

    override fun list(dirPath: String): List<RemoteEntry> {
        val url = base.newBuilder().addPathSegments("api/v1/list")
            .addQueryParameter("dir", if (dirPath.isEmpty()) "/" else dirPath).build()
        client.newCall(req(Request.Builder().url(url).get())).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "list ${r.code}")
            val arr = JSONObject(r.body!!.string()).getJSONArray("entries")
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RemoteEntry(
                    fileName = o.getString("name"),
                    etag = o.optString("etag").ifEmpty { null },
                    sizeBytes = if (o.has("size")) o.getLong("size") else null,
                )
            }.filter { !it.fileName.isEmpty() }
        }
    }

    override fun get(filePath: String): String? {
        client.newCall(req(Request.Builder().url(fileUrl(filePath)).get())).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw HttpError(r.code, "get ${r.code}")
            return r.body!!.string()
        }
    }

    override fun put(filePath: String, wholeFileText: String, ifMatch: String?): PutResult =
        doPut(filePath, wholeFileText.toByteArray()) { b ->
            if (ifMatch != null) b.header("If-Match", ifMatch)
        }

    override fun putIfAbsent(filePath: String, wholeFileText: String): PutResult =
        doPut(filePath, wholeFileText.toByteArray()) { it.header("If-None-Match", "*") }

    override fun putFile(relativePath: String, bytes: ByteArray): PutResult =
        doPut(relativePath, bytes) { }

    override fun getFile(relativePath: String): ByteArray? {
        client.newCall(req(Request.Builder().url(fileUrl(relativePath)).get())).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw HttpError(r.code, "getFile ${r.code}")
            return r.body!!.bytes()
        }
    }

    private inline fun doPut(path: String, bytes: ByteArray, headers: (Request.Builder) -> Unit): PutResult {
        return try {
            val b = Request.Builder().url(fileUrl(path)).put(bytes.toRequestBody())
            headers(b)
            client.newCall(req(b)).execute().use { r ->
                when {
                    r.isSuccessful -> PutResult.WRITTEN(r.header("ETag"))
                    r.code == 412 -> PutResult.PRECONDITION_FAILED
                    else -> PutResult.FAILED
                }
            }
        } catch (_: IOException) { PutResult.FAILED }
    }

    override fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean {
        val body = JSONObject(mapOf("from" to fromPath, "to" to toPath, "overwrite" to overwrite))
            .toString().toRequestBody("application/json".toMediaTypeOrNull())
        val url = base.newBuilder().addPathSegments("api/v1/move").build()
        return try {
            client.newCall(req(Request.Builder().url(url).post(body))).execute().use { it.isSuccessful }
        } catch (_: IOException) { false }
    }

    override fun delete(filePath: String): Boolean = try {
        client.newCall(req(Request.Builder().url(fileUrl(filePath)).delete())).execute().use {
            it.isSuccessful || it.code == 404
        }
    } catch (_: IOException) { false }

    override fun mkcol(dirPath: String): Boolean {
        val body = JSONObject(mapOf("dir" to dirPath)).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val url = base.newBuilder().addPathSegments("api/v1/mkcol").build()
        return try {
            client.newCall(req(Request.Builder().url(url).post(body))).execute().use { it.isSuccessful }
        } catch (_: IOException) { false }
    }
}
```

## 2. Login — obtaining the token

A new `ui/login/LoginScreen.kt` + `LoginViewModel.kt` posts the fabric
credentials to **xx-chat's** `POST /api/v1/fabric/login` (see
`xx-chat/docs/FABRIC-AUTH.md`) and receives `{user_id, token, expires_at}`. The
app stores **only the `token`** (and `expires_at`) — it never needs the
password again until the token expires.

`net/FabricLogin.kt` (sketch):

```kotlin
data class FabricSession(val userId: String, val token: String, val expiresAt: String)

fun login(base: HttpUrl, client: OkHttpClient, user: String, pass: String): FabricSession {
    val body = JSONObject(mapOf("username" to user, "password" to pass)).toString()
        .toRequestBody("application/json".toMediaTypeOrNull())
    val url = base.newBuilder().addPathSegments("api/v1/fabric/login").build()
    client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
        if (r.code == 401) throw HttpError(401, "invalid_credentials")
        if (!r.isSuccessful) throw HttpError(r.code, "login ${r.code}")
        val o = JSONObject(r.body!!.string())
        return FabricSession(o.getString("user_id"), o.getString("token"), o.getString("expires_at"))
    }
}
```

Note the login endpoint lives on xx-chat, and the notes API lives on
xxnote-server — two hosts. Either keep two `OneHostInterceptor`-guarded
`OkHttpClient`s (one per origin), or if both are fronted by the same estate
gateway, one host with two path prefixes.

## 3. Credential storage — `data/Entities.kt` + `CredentialVault`

`CredentialVault`/`KeystoreKeyOps` already seal arbitrary bytes at rest (R9), so
they are unchanged — they now seal the **session token** instead of a DSM
password. `CredentialEntity` (still a singleton row) changes:

- **add** `userId: String` (the fabric id — informational; the server derives
  the path from the token, the client never sends `userId`);
- **repurpose** `sealedSecret` to hold the sealed token;
- **`host`/`port`** become the fabric backend origin (compiled-in or
  remotely-configured), not a user-typed Synology address;
- **`basePath`** is dropped — the server always hands back the one vault root.

A Room migration adds `userId` and rewrites the row's meaning. Because the row
is a singleton, the migration can also simply drop and recreate it and force a
re-login on upgrade (simpler, and the sealed DSM password is worthless against
the fabric backend anyway).

## 4. Setup flow + manifest

- `ui/setup/SetupLogic.kt` steps 1–2 (host + DSM account) are replaced by the
  login screen. Steps 3–7 operate on the `RemoteFiles` port and mostly survive;
  the folder-picker (`PREFIX_CANDIDATES`) collapses to "the server always hands
  you your one vault root."
- The 401 UX (`authReopenLines`) is reused, pointed at re-login instead of
  re-entering a DSM password — so `SyncEngine`'s R10 "stale creds surfaced, no
  silent retry" behavior is preserved for free.
- `AndroidManifest.xml` / network-security-config: add the fabric backend host
  to the trusted-domain allowlist (same mechanism as the existing DSM entry),
  HTTPS-only.

## 5. Keep the DSM path?

Recommended: **keep `WebDavClient` as a selectable alternate profile** — design.md
already anticipated "a second sync backend," and this is literally it. Nothing
about the DSM-direct path breaks; the fabric path is additive.

## 6. Test parity

Add a JVM test for `FabricFilesClient` against `MockWebServer` mirroring
`WebDavClientTest`'s cases (status→PutResult mapping, 404→null, 401 surfaced,
ETag round-trip, redirect refused). The engine tests already run against
`RemoteFiles` fakes and need no change.
