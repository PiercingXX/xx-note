package com.piercingxx.xxnote.ui.setup

import com.piercingxx.xxnote.core.Etag
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.net.HttpError
import com.piercingxx.xxnote.net.WebDavClient
import com.piercingxx.xxnote.sync.RemoteEntry
import com.piercingxx.xxnote.sync.RemoteFiles
import com.piercingxx.xxnote.sync.SyncEngine
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * WS6's pure setup logic: endpoint parsing, reach classification, prefix
 * probing, the §12 import disclosure, ETag-mode detection (§4.2), and the
 * persisted-config payload assembly. Every function here is JVM-safe —
 * no Android imports — and every network-touching function takes an
 * injected [RemoteFiles] so tests drive it with scripted fakes.
 *
 * Rulings this object pins (beyond the literal spec):
 * - "Id-less" means the frontmatter carries NO non-blank `id:` — the same
 *   rule [com.piercingxx.xxnote.data.VaultStore]'s scan applies when it
 *   assigns ids on first read. Files with a present-but-malformed id are
 *   NOT counted id-less (the scan keeps such ids); SyncEngine flags them
 *   separately.
 * - ETag mode is `"etag"` only when a NON-empty listing shows a STRONG
 *   getetag on EVERY entry (RFC 7232: a `W/` weak tag can never guard an
 *   `If-Match`, so it counts as unusable — honoring one would 412 every
 *   write into a fork storm). An EMPTY folder observed nothing unusable and
 *   therefore selects `"etag"`; any hole or weakness falls back conservatively
 *   (§15: the fallback guarantee is weaker and must not be assumed strong).
 */
object SetupLogic {

    /** §4.2: DSM WebDAV HTTPS default. HTTP 5005 fails closed (R8). */
    const val DEFAULT_PORT = 5006

    /** Keystore alias for the sealed DSM password (R9) — matches SyncGraph's reader. */
    const val VAULT_KEY_ALIAS = "xxnote-vault"

    const val ETAG_MODE_ETAG = "etag"
    const val ETAG_MODE_FALLBACK = "fallback"

    // settingDao keys. device_name MUST stay equal to SyncGraph.SETTING_DEVICE_NAME,
    // which the engine reads; host/port/base_path/user/etag_mode are WS6-owned.
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_BASE_PATH = "base_path"
    const val KEY_USER = "user"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_ETAG_MODE = "etag_mode"

    /** §4.2 candidate prefixes for the vault, probed with PROPFIND Depth:1 at step 4. */
    val PREFIX_CANDIDATES = listOf(
        "home",
        "home/Drive",
        "home/Drive/Notes",
        "Drive",
        "Notes",
    )

    // ---- step 1: endpoint -----------------------------------------------------

    data class Endpoint(val host: String, val port: Int)

    /**
     * Words explaining why step 1 cannot continue yet, or null when the pair
     * parses. Accepts `https://` prefixes (stripped); rejects cleartext
     * schemes outright (HTTPS-only, R8). A single `:port` suffix inside the
     * host field is tolerated and fills the port in.
     */
    fun endpointProblem(rawHost: String, rawPort: String): String? {
        val host = stripScheme(rawHost.trim())
        if (host.isEmpty()) return "type the NAS address first"
        if (rawHost.trim().startsWith("http://", ignoreCase = true)) {
            return "this app speaks HTTPS only — there is no plain-HTTP mode"
        }
        if (host.startsWith("//")) return "the address should be just the host, like nas.tailnet.ts.net"
        if (host.contains('/') || host.contains('\\') || host.contains(' ')) {
            return "a host has no slashes or spaces — like nas.tailnet.ts.net"
        }
        val colon = host.lastIndexOf(':')
        if (colon > 0) {
            val suffixIsPort = host.indexOf(':') == colon &&
                host.substring(colon + 1).toIntOrNull() in 1..65535
            if (!suffixIsPort) {
                return "the address should be just the host — put a port only in the port field"
            }
        } else if (host.contains(':')) {
            return "the address should be just the host — put a port only in the port field"
        }
        val portText = rawPort.trim()
        if (portText.isNotEmpty()) {
            val port = portText.toIntOrNull() ?: return "$portText is not a port number"
            if (port !in 1..65535) return "$port is not a usable port (1–65535)"
        }
        return null
    }

    /** Parses a pair already vetted by [endpointProblem]; throws otherwise. */
    fun endpoint(rawHost: String, rawPort: String): Endpoint {
        val problem = endpointProblem(rawHost, rawPort)
        require(problem == null) { problem.orEmpty() }
        var host = stripScheme(rawHost.trim())
        val portField = rawPort.trim().toIntOrNull()
        if (portField != null) {
            // The explicit port field wins; drop any ":port" suffix typed
            // into the host field rather than fighting over it.
            val idx = host.lastIndexOf(':')
            if (idx > 0) host = host.substring(0, idx)
            return Endpoint(host, portField)
        }
        val idx = host.lastIndexOf(':')
        if (idx > 0) return Endpoint(host.substring(0, idx), host.substring(idx + 1).toInt())
        return Endpoint(host, DEFAULT_PORT)
    }

    /** Only HTTPS may be stripped; anything else stays so the scheme check rejects it (R8). */
    private fun stripScheme(host: String): String =
        if (host.startsWith("https://", ignoreCase = true)) host.substring("https://".length) else host

    // ---- step 2/3: client + reach ---------------------------------------------

    /**
     * The one client shape used across steps 3–7. HTTPS hard-wired: the
     * production default scheme of [WebDavClient] is the only path here.
     */
    fun buildClient(
        endpoint: Endpoint,
        basePath: String,
        user: String,
        password: String,
    ): RemoteFiles = WebDavClient(
        host = endpoint.host,
        port = endpoint.port,
        basePath = basePath,
        username = user,
        password = password,
    )

    /** What one real request against the server came back as (§12 step 3, R10). */
    sealed interface Reach {

        /** Server answered 207 Multi-Status. [verbatim] names the status. */
        data class Reachable(val entries: List<RemoteEntry>, val verbatim: String) : Reach

        /** HTTP error status, surfaced verbatim (§15 row: WebDAV package stopped…). */
        data class Refused(val status: Int, val verbatim: String?) : Reach

        /** TLS refused to establish — hard fail, no bypass affordance (§15). */
        data class TlsFailure(val verbatim: String?) : Reach

        /** No HTTP answer at all: tailnet down, wrong port, NAS off (§15 offline row). */
        data class Unreachable(val verbatim: String?) : Reach
    }

    /** One real PROPFIND against [dirPath], classified. Blocking; call off main. */
    fun reach(client: RemoteFiles, dirPath: String = ""): Reach = try {
        Reach.Reachable(client.list(dirPath), "PROPFIND ${displayPath(dirPath)} → HTTP 207 Multi-Status")
    } catch (e: HttpError) {
        Reach.Refused(e.status, e.message)
    } catch (e: SSLException) {
        Reach.TlsFailure(e.message)
    } catch (e: IOException) {
        Reach.Unreachable(e.message)
    }

    /** Plain words distinguishing unreachable from HTTP-error (R10 tone). */
    fun describe(reach: Reach, host: String): String = when (reach) {
        is Reach.Reachable -> "connected — the server answered"
        is Reach.Refused -> when (reach.status) {
            401, 403 -> "HTTP ${reach.status} — the server rejected the account: wrong user or password"
            404 -> "HTTP 404 — nothing lives at that address"
            else -> "HTTP ${reach.status} — the server refused the request"
        }
        is Reach.TlsFailure ->
            "TLS certificate problem talking to $host — stopping; there is no bypass in this app"
        is Reach.Unreachable ->
            "no answer from $host — tailnet down, wrong port, or the NAS is asleep"
    }

    // ---- step 4: folder browse ---------------------------------------------------

    /** Probes each §4.2 candidate prefix in order. Blocking; call off main. */
    fun probePrefixes(
        client: RemoteFiles,
        candidates: List<String> = PREFIX_CANDIDATES,
    ): Map<String, Reach> = candidates.associateWith { reach(client, it) }

    /** Candidates that exist, in probe order — the pickable list of step 4. */
    fun existingFolders(probes: Map<String, Reach>): List<String> =
        probes.filterValues { it is Reach.Reachable }.keys.toList()

    fun displayPath(path: String): String = "/${path.trim('/')}/"

    /**
     * Normalizes a typed or picked vault folder to the canonical stored form:
     * slash-trimmed segments joined by `/`, dot-leading segments rejected so
     * `.xxnote`'s trash namespace can never become the vault root. Null when
     * unusable.
     */
    fun normalizeBasePath(raw: String): String? {
        val segments = raw.trim().trim('/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { it.startsWith(".") }) return null
        if (segments.any { it.contains('\\') }) return null
        return segments.joinToString("/")
    }

    /**
     * MKCOLs [basePath] level by level (`mkdir -p` over WebDAV): parents may
     * not exist and RFC 4918 creates only the final segment. True only when
     * every level reports created-or-already-exists.
     */
    fun ensureFolders(client: RemoteFiles, basePath: String): Boolean {
        var partial = ""
        var allOk = true
        for (segment in basePath.split('/').filter { it.isNotEmpty() }) {
            partial = if (partial.isEmpty()) segment else "$partial/$segment"
            allOk = client.mkcol(partial) && allOk
        }
        return allOk
    }

    // ---- step 5: confirm + disclosure ------------------------------------------------

    /** Direct children that would enter the vault: visible `.md` files only. */
    fun countMarkdown(entries: List<RemoteEntry>): Int =
        entries.count { entry ->
            entry.fileName.endsWith(".md") && !entry.fileName.startsWith(".")
        }

    /**
     * P2.10 subfolder detection: true when the listing holds a collection
     * sync will never walk. Dot-collections (`.xxnote`'s trash namespace)
     * and the §10 attachments dir are known non-note dirs, not disclosures;
     * the vault root answering its own PROPFIND is already excluded by
     * [com.piercingxx.xxnote.net.WebDavClient]. Nested vaults are out of
     * scope by design — flat-root engine.
     */
    fun hasUnsyncedSubfolders(entries: List<RemoteEntry>): Boolean =
        entries.any { entry ->
            entry.collection &&
                !entry.fileName.startsWith(".") &&
                entry.fileName != SyncEngine.ATTACHMENTS_DIR.trimEnd('/')
        }

    /**
     * The one-line plain words for [hasUnsyncedSubfolders], or null when
     * there is nothing to disclose. Files inside server-side subfolders stay
     * on the server — said once at confirm instead of discovered never.
     */
    fun subfolderLine(hasSubfolders: Boolean): String? =
        if (hasSubfolders) {
            "this folder has subfolders — files inside them stay on the server"
        } else {
            null
        }

    /**
     * Import-scan rule: no USABLE id — the line is absent, blank, or not a
     * canonical ULID. Deliberately the SAME predicate [com.piercingxx.xxnote.sync.ImportPass]
     * stamps by, so the confirm step's count is exactly the set import will
     * rewrite (a present-but-invalid id would never sync — the engine only
     * matches canonical ULIDs — and is disclosed as receiving one).
     */
    fun isIdLess(wholeFileText: String): Boolean =
        Frontmatter.parse(wholeFileText).id?.takeIf(Ulid::isValid) == null

    /**
     * The §12 disclosure, EXACTLY as specified, byte for byte. Zero files is
     * design §12's other branch: "empty folder".
     */
    fun disclosureText(found: Int): String =
        if (found == 0) {
            "empty folder"
        } else {
            "$found existing .md files — these will be imported, ids assigned where missing, nothing will be overwritten"
        }

    /** The counted detail line under the disclosure: how many get ids assigned. */
    fun idlessLine(found: Int, idLess: Int): String =
        if (found == 0) {
            "nothing to import"
        } else {
            "$idLess of $found have no id yet — those receive one on import"
        }

    /** One PROPFIND plus a GET per `.md`, counted. Blocking; call off main. */
    fun confirmFolder(
        client: RemoteFiles,
        basePath: String,
    ): ConfirmScan {
        val entries = client.list(basePath)
        val mdEntries = entries.filter { it.fileName.endsWith(".md") && !it.fileName.startsWith(".") }
        var idLess = 0
        for (entry in mdEntries) {
            val text = client.get(if (basePath.isEmpty()) entry.fileName else "$basePath/${entry.fileName}")
            if (text != null && isIdLess(text)) idLess++
        }
        return ConfirmScan(
            found = mdEntries.size,
            idLess = idLess,
            etagMode = etagModeOf(entries),
            weakEtags = mdEntries.any { Etag.isWeak(it.etag) },
            hasSubfolders = hasUnsyncedSubfolders(entries),
        )
    }

    data class ConfirmScan(
        val found: Int,
        val idLess: Int,
        val etagMode: String,
        /**
         * True when any visible `.md` answered with a WEAK ETag (`W/`): the
         * confirm step must say so in plain words now instead of letting the
         * weakness fork-storm later (weak tags cannot lock writes, so sync
         * runs the §4.2 fallback).
         */
        val weakEtags: Boolean,
        /** P2.10: the vault root has subcollections sync will never walk. */
        val hasSubfolders: Boolean = false,
    )

    /**
     * §4.2 detection: getetag must be present and STRONG on every listed
     * FILE entry. An empty listing observes nothing unusable and selects ETAG
     * mode — absence of evidence is not evidence of a weak server.
     * Subcollections carry no validator by their nature; they are not
     * evidence about the server either way (P2.10), so only files decide.
     */
    fun etagModeOf(entries: List<RemoteEntry>): String =
        if (entries.filterNot { it.collection }.all { Etag.isStrong(it.etag) }) {
            ETAG_MODE_ETAG
        } else {
            ETAG_MODE_FALLBACK
        }

    /**
     * The confirmation sentence stating which guarantee first sync will run
     * under (§15). [weakEtags] names weakness as the reason for the fallback
     * when the listing showed `W/` tags — those can't lock writes.
     */
    fun etagLine(mode: String, weakEtags: Boolean = false): String =
        if (mode == ETAG_MODE_ETAG) {
            "this server returns ETags on every file — sync can lock writes properly"
        } else if (weakEtags) {
            "some files answer with weak ETags (a W/ prefix) — weak tags can't lock writes, " +
                "so sync checks each file's contents right before writing; weaker protection, known and stated (§4.2)"
        } else {
            "this server does not return usable ETags — sync checks each file's contents against its last-synced copy right before writing, so a concurrent edit is forked, never overwritten; weaker protection, known and stated (§4.2)"
        }

    // ---- step 6: device name ------------------------------------------------------------

    /** Editable default: Build.MODEL, trimmed and whitespace-collapsed; never blank. */
    fun deviceNameOrDefault(raw: String, modelFallback: String): String {
        val candidate = raw.trim().replace(Regex("\\s+"), " ")
        return candidate.ifEmpty {
            modelFallback.trim().replace(Regex("\\s+"), " ").ifEmpty { "xx-device" }
        }
    }

    // ---- step 7: persistence payload + outcome words ---------------------------------------

    /**
     * Everything step 7 persists, assembled before anything touches storage:
     * the credential row's fields (password sealed separately, NEVER carried
     * here) and the ordered setting rows. [credentialHost] follows
     * SyncGraph's documented `host:port` convention because the credential
     * table has no port column.
     */
    data class ConfigPayload(
        val credentialHost: String,
        val basePath: String,
        val user: String,
        val settings: List<Pair<String, String>>,
    )

    fun configPayload(
        endpoint: Endpoint,
        basePath: String,
        user: String,
        deviceName: String,
        etagMode: String,
    ): ConfigPayload = ConfigPayload(
        credentialHost = "${endpoint.host}:${endpoint.port}",
        basePath = basePath,
        user = user,
        settings = listOf(
            KEY_HOST to endpoint.host,
            KEY_PORT to endpoint.port.toString(),
            KEY_BASE_PATH to basePath,
            KEY_USER to user,
            KEY_DEVICE_NAME to deviceName,
            KEY_ETAG_MODE to etagMode,
        ),
    )

    /** Per-verdict tally after a Completed first pass (design §12: progress count). */
    fun completedSummary(pulled: Int, pushed: Int, merged: Int, forked: Int, trashed: Int, resurrected: Int, nothing: Int): String =
        "first sync finished — pulled $pulled · pushed $pushed · merged $merged · forked $forked · trashed $trashed · restored $resurrected · unchanged $nothing"

    /** §15 auth-failure row, verbatim status first, reopen behavior second. */
    fun authReopenLines(status: Int): List<String> = listOf(
        "first sync stopped: the server answered HTTP $status.",
        "reopening the account step — everything pre-filled except the password.",
    )

    /** §9 vault-level safety words when first sync halts before touching anything. */
    fun haltedLines(wouldTrash: Int, liveNotes: Int): List<String> = listOf(
        "first sync stopped before changing anything:",
        "$wouldTrash of $liveNotes notes would move to trash — this looks like the wrong folder mounted.",
        "pick the folder again.",
    )
}
