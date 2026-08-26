package com.piercingxx.xxnote.sync

/**
 * The ports SyncEngine talks to. Both sides are file-shaped and every text
 * here obeys the WHOLE-FILE LAW (pinned during WS2 review): a "body" is the
 * ENTIRE `.md` file — YAML frontmatter block plus Markdown body, exactly the
 * bytes on disk/server. Metadata-only edits therefore dirty a snapshot.
 *
 * Implementations: [com.piercingxx.xxnote.data.VaultStore] implements
 * [LocalFiles]; [com.piercingxx.xxnote.net.WebDavClient] implements
 * [RemoteFiles]. Engine tests run against fakes — no Android, no network.
 */

/**
 * One entry of a PROPFIND Depth:1 listing of the live vault directory.
 *
 * Subcollections ride along as [collection] rows so their presence can be
 * DISCLOSED (P2.10: nested folders are not synced — flat-root by design);
 * they are never walked, never fetched, and every note-shaped consumer
 * filters on `.md` anyway. The listing's own directory is excluded by the
 * client — a subfolder means a child, not the vault root itself.
 */
data class RemoteEntry(
    val fileName: String,
    val etag: String?,
    val sizeBytes: Long?,
    val collection: Boolean = false,
)

sealed interface PutResult {
    /**
     * Server accepted the write. [etag] is the ETag response header returned
     * by the server for the new version, null when the server omits it.
     */
    data class WRITTEN(val etag: String?) : PutResult

    /** `If-Match`/`If-None-Match` rejected — someone wrote first. Row 12: re-plan. */
    data object PRECONDITION_FAILED : PutResult

    /** Transport or HTTP failure; see the client's error surface. */
    data object FAILED : PutResult

    /**
     * The write was refused BEFORE touching the wire — produced only by the
     * sync layer's §4.2 lock law ([SyncEngine.guardedPut] and the Setup
     * import pass), never by [com.piercingxx.xxnote.net.WebDavClient].
     * A base snapshot or listing entry with a null or weak ETag must never
     * become an unconditional PUT, so such a write is refused outright and
     * [reason] carries the plain words for the sync screen. A refused write
     * lost nothing: both sides keep their bytes.
     */
    data class Refused(val reason: String) : PutResult
}

/**
 * §4.2's two operating modes, detected once at Setup (R10: stated because the
 * guarantee differs) and persisted under `etag_mode`.
 *
 * - [ETAG] — every listed file answered with a strong ETag; writes lock with
 *   `If-Match` against the recorded base ETag.
 * - [FALLBACK] — ETags are missing or weak on this server; before any body
 *   PUT the engine re-reads the file and compares it against the base
 *   snapshot's bytes (full-body SHA-256). The lost-update race is narrowed,
 *   not closed — weaker protection, known and stated.
 *
 * [fromStored] maps the persisted string; an absent or unknown value falls
 * back to [FALLBACK] — installs configured before modes existed get the mode
 * that can never blind-write, which is the safe direction.
 */
enum class EtagMode(val stored: String) {
    ETAG("etag"),
    FALLBACK("fallback"),
    ;

    companion object {
        fun fromStored(raw: String?): EtagMode = entries.firstOrNull { it.stored == raw } ?: FALLBACK
    }
}

/** The far side: the WebDAV vault. One host, enforced upstream (R8). */
interface RemoteFiles {
    /** Depth:1 listing of [dirPath]; paths are vault-relative, `/`-separated. */
    fun list(dirPath: String): List<RemoteEntry>

    /** Whole-file text, or null on 404. */
    fun get(filePath: String): String?

    /** Conditional write; [ifMatch] null means unconditional. */
    fun put(filePath: String, wholeFileText: String, ifMatch: String?): PutResult

    /** Create-only write (`If-None-Match: *`) — §6 row 2. */
    fun putIfAbsent(filePath: String, wholeFileText: String): PutResult

    /**
     * Rename/trash move. True on 2xx. [overwrite] sends RFC 4918's `Overwrite`
     * header: `T` (the default — rename semantics) or `F`, which makes the
     * server reject the move when the destination already exists instead of
     * silently clobbering it (row 7 trash moves must never destroy a copy).
     */
    fun move(fromPath: String, toPath: String, overwrite: Boolean = true): Boolean

    /**
     * Real unlink. Per D9 this is called ONLY by trash expiry against files
     * carrying `trashedAt:` — belt-and-braces assertion lives in the caller.
     */
    fun delete(filePath: String): Boolean

    /** Create a collection (folder skeleton). True on 2xx or already-exists. */
    fun mkcol(dirPath: String): Boolean

    // --- binary attachments (WS10, §10/D13) ---------------------------------

    /**
     * Unconditional raw-bytes write at [relativePath] (`attachments/<16-hex>.<ext>`).
     * Content-addressed attachment files are immutable, so no `If-Match` is ever
     * needed — "exists or it doesn't" is the whole sync question. Status mapping
     * identical to [put].
     */
    fun putFile(relativePath: String, bytes: ByteArray): PutResult

    /** Raw bytes at [relativePath], or null on 404. Other failures per [get]. */
    fun getFile(relativePath: String): ByteArray?
}

/** One note as the local side holds it. */
data class LocalNote(
    val id: String,
    /** Path relative to the vault root, e.g. `01J9F2…-grocery-list.md`. */
    val path: String,
    val wholeFileText: String,
    val trashed: Boolean,
)

/** The near side: the `filesDir/vault` mirror backed by Room (cache + outbox). */
interface LocalFiles {
    fun listLive(): List<LocalNote>
    fun listTrashed(): List<LocalNote>

    /** By frontmatter `id`, never by path (D3/R7). Null when unknown. */
    fun read(id: String): LocalNote?

    /** Write-through: temp-then-rename on the mirror, Room row rebuilt. */
    fun write(id: String, wholeFileText: String)

    /** Move the mirror file into `.xxnote/trash/` + stamp `trashedAt:` (D9). */
    fun trash(id: String)

    /** Bring a trashed note back live. */
    fun restore(id: String)

    /**
     * Row 8 fork-of-trash: re-stamps the TRASHED note [id]'s bytes in place as
     * their own note under [newId] — still inside trash, frontmatter passed
     * through [stamp] (which applies a fresh id plus `conflictOf:` /
     * `conflictAt:`). The caller then writes the surviving side live under the
     * original id, so both texts exist after the pass (§15: never lose text).
     * The Room row follows the re-stamp: old identity retired, new one keyed
     * to the same trashed path.
     */
    fun forkTrashedCopy(id: String, newId: String, stamp: (String) -> String)

    /** `<ulid>-<slug>.md` for a fresh note (§8). */
    fun basePathFor(id: String, title: String): String

    /** First unused variant of [base] (`_1`, `_2`, …) — forks (§7). */
    fun freeName(base: String): String

    /**
     * D9 expiry sweep, local half: delete every trashed mirror file whose
     * frontmatter `trashedAt:` is strictly older than [now]-[olderThanMs],
     * removing its row with it; a copy without a parsable stamp is NEVER
     * deleted. Returns the number of entries purged. Default no-op so fakes
     * and other implementors can opt out; VaultStore implements the real
     * purge (H4).
     */
    fun purgeExpiredTrash(olderThanMs: Long, now: Long): Int = 0
}

/** One row of the sync log — the reason-string store behind R10. */
data class SyncLogEntry(
    val noteId: String?,
    val verdict: String,
    val reason: String,
    val ok: Boolean,
    val detail: String? = null,
)

/**
 * Sync bookkeeping: base snapshots (D7), the durable outbox, the log.
 * Implemented over Room; faked in engine tests.
 */
interface SyncBookkeeping {
    fun baseOf(noteId: String): com.piercingxx.xxnote.core.BaseSnapshot?
    fun recordBase(noteId: String, wholeFileText: String, etag: String?)
    fun forgetBase(noteId: String)

    fun log(entry: SyncLogEntry)

    /** Enqueue an idempotent op ('put'|'move'|'trash'|'delete'|'attach'). */
    fun enqueueOp(noteId: String, op: String, payload: String)

    /** Ops not yet confirmed applied, oldest first. */
    fun pendingOps(): List<OutboxOp>

    fun markOpDone(opId: Long)
    fun markOpFailed(opId: Long, error: String)
}

data class OutboxOp(
    val id: Long,
    val noteId: String,
    val op: String,
    val payload: String,
    val attempts: Int,
    val lastError: String?,
)

/**
 * Pure extraction of §10 attachment references — `attachments/<16-hex>.<ext>`
 * — from WHOLE-FILE texts. One regex, one truth: [com.piercingxx.xxnote.data.AttachmentStore]
 * (orphan sweep) and [SyncEngine] (upload-before-body ordering) both read this
 * so their notions of "referenced" can never diverge. Over-matching is safe:
 * it only ever makes sweeping more conservative and uploads more eager.
 */
object AttachmentRefs {

    /** The reference shape: exactly 16 hex chars, 1–8 char extension. */
    val PATTERN = Regex("attachments/([0-9a-fA-F]{16})\\.[0-9A-Za-z]{1,8}")

    /** Normalized lowercase 16-hex tokens referenced by any of [wholeFileTexts]. */
    fun hashes(wholeFileTexts: List<String>): Set<String> =
        PATTERN.findAll(wholeFileTexts.joinToString("\n"))
            .map { it.groupValues[1].lowercase() }
            .toSet()
}

/** One stored attachment row, reduced to what upload ordering needs (§10). */
data class AttachmentRow(
    /** Full SHA-256 hex of the bytes. */
    val hash: String,
    /** File extension as stored (already normalized). */
    val ext: String,
    /** True once a copy is confirmed on the far side. */
    val remoteKnown: Boolean,
)

/**
 * The attachment half of §10's upload-before-body law, as the engine sees it.
 * Implemented by data.AttachmentStore over Room + the mirror's `attachments/`
 * dir; faked by engine tests. Synchronous like every other port here — Room
 * calls ride `runBlocking` inside the implementation (VaultStore precedent).
 *
 * The engine NEVER writes attachment rows or files through this port; it only
 * reads state, uploads bytes via [RemoteFiles.putFile], and marks success.
 */
interface Attachments {

    /** The row whose filename carries [prefix16], or null when none is stored. */
    fun rowByPrefix(prefix16: String): AttachmentRow?

    /** Bytes of the local cache file for [prefix16], or null when absent. */
    fun localBytes(prefix16: String): ByteArray?

    /** Records that [hash]'s copy is confirmed on the far side. Unknown hashes are ignored. */
    fun markRemoteKnown(hash: String)
}
