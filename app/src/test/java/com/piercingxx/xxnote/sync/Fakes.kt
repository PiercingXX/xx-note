package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.BaseSnapshot
import com.piercingxx.xxnote.core.Slug
import java.io.IOException

/**
 * Pure-JVM port fakes for [SyncEngine] tests — no Android, no network, no
 * Robolectric. They mirror the real implementations' contract shapes:
 * InMemoryLocal mirrors VaultStore's id-keyed mirror, InMemoryRemote mirrors
 * WebDavClient's status-mapping (including genuine `If-Match` checking and a
 * scripted 412/FAILED mode), InMemoryBook holds Room's three tables in maps.
 */

class InMemoryLocal : LocalFiles {

    private class Stored(val path: String, var text: String)

    /** id → stored file. Trash is a flag; bytes are never touched (D9). */
    private val notes = LinkedHashMap<String, Stored>()
    private val trashedIds = LinkedHashSet<String>()

    val writeCalls = mutableListOf<Pair<String, String>>()
    val trashCalls = mutableListOf<String>()
    val restoreCalls = mutableListOf<String>()

    fun add(id: String, path: String, text: String, trashed: Boolean = false) {
        notes[id] = Stored(path, text)
        if (trashed) trashedIds += id else trashedIds.remove(id)
    }

    fun remove(id: String) {
        notes.remove(id)
        trashedIds.remove(id)
    }

    fun clear() {
        notes.clear()
        trashedIds.clear()
    }

    fun paths(): Set<String> = notes.values.map { it.path }.toSet()

    override fun listLive(): List<LocalNote> =
        notes.filterKeys { it !in trashedIds }.map { (id, s) -> LocalNote(id, s.path, s.text, false) }

    override fun listTrashed(): List<LocalNote> =
        notes.filterKeys { it in trashedIds }.map { (id, s) -> LocalNote(id, s.path, s.text, true) }

    override fun read(id: String): LocalNote? =
        notes[id]?.let { LocalNote(id, it.path, it.text, id in trashedIds) }

    override fun write(id: String, wholeFileText: String) {
        writeCalls += id to wholeFileText
        val existing = notes[id]
        val path = existing?.path ?: basePathFor(id, titleOf(wholeFileText))
        notes[id] = Stored(path, wholeFileText)
        trashedIds.remove(id)
    }

    override fun trash(id: String) {
        require(notes.containsKey(id)) { "trash: unknown note $id" }
        trashedIds += id
        trashCalls += id
    }

    override fun restore(id: String) {
        check(trashedIds.remove(id)) { "restore: $id is not trashed" }
        restoreCalls += id
    }

    override fun forkTrashedCopy(id: String, newId: String, stamp: (String) -> String) {
        val stored = notes[id]
            ?: throw IllegalArgumentException("forkTrashedCopy: unknown note $id")
        check(id in trashedIds) { "forkTrashedCopy: $id is not trashed" }
        val stamped = stamp(stored.text)
        check(
            com.piercingxx.xxnote.core.Frontmatter.parse(stamped).id == newId,
        ) { "forkTrashedCopy($id → $newId): stamp did not apply the new identity" }
        // Re-stamped in place under its own path; still in trash.
        notes[newId] = Stored(basePathFor(newId, titleOf(stamped)), stamped)
        trashedIds += newId
    }

    override fun basePathFor(id: String, title: String): String =
        "$id-${Slug.of(title)}.md"

    /** First unused `<stem>_<n><ext>` variant — free names pass through unchanged. */
    override fun freeName(base: String): String {
        val taken = notes.values.map { it.path.substringAfterLast('/') }.toHashSet()
        var candidate = base
        var n = 1
        while (candidate.substringAfterLast('/') in taken) {
            candidate = variantOf(base, n)
            n++
        }
        return candidate
    }

    /**
     * H4 local half, mirroring VaultStore's law: a trashed entry whose bytes
     * carry a parsable `trashedAt:` strictly older than [now]-[olderThanMs]
     * is removed entirely; unstamped copies are NEVER deleted.
     */
    override fun purgeExpiredTrash(olderThanMs: Long, now: Long): Int {
        val expired = notes.filterKeys { it in trashedIds }.filterValues { stored ->
            val stamp = com.piercingxx.xxnote.core.Frontmatter.parse(stored.text).trashedAt
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?: return@filterValues false // no stamp → never expires
            now - stamp.toEpochMilli() > olderThanMs
        }.keys
        expired.forEach { remove(it) }
        return expired.size
    }

    private fun variantOf(base: String, n: Int): String {
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) + "_$n" + base.substring(dot) else "${base}_$n"
    }

    private fun titleOf(wholeFileText: String): String =
        com.piercingxx.xxnote.core.Frontmatter.parse(wholeFileText).title ?: ""
}

class InMemoryRemote : RemoteFiles {

    enum class PutMode { NORMAL, ALWAYS_412, ALWAYS_FAIL }

    private val files = LinkedHashMap<String, String>()
    private val etags = HashMap<String, String>()
    private var etagSeq = 0

    /** Controls list/get/move reachability — a dead tailnet throws IOException. */
    var reachable = true

    /** Scripted PUT outcome; NORMAL enforces real `If-Match` semantics. */
    var putMode = PutMode.NORMAL

    /**
     * Scripted attachment-PUT ([putFile]) outcome, independent of [putMode] so
     * tests can fail the attachment while the note body succeeds (§10 deferral).
     */
    var putFileMode = PutMode.NORMAL

    /**
     * When non-null, list/get throw [com.piercingxx.xxnote.net.HttpError] with
     * this status — a scripted 401/403 credential refusal (H2).
     */
    var authStatus: Int? = null

    /** Paths whose NEXT put is rejected with 412 regardless of mode (one-shot). */
    val failNextPut412 = LinkedHashSet<String>()

    /**
     * Per-path ETag handed back inside [PutResult.WRITTEN], mirroring the
     * server's ETag response header. Paths without a scripted value echo the
     * freshly minted stored etag; a scripted null models a server that omits
     * the header.
     */
    private val scriptedPutEtags = HashMap<String, String?>()

    fun scriptPutEtag(path: String, etag: String?) {
        scriptedPutEtags[path] = etag
    }

    val getCalls = HashMap<String, Int>()
    val puts = mutableListOf<PutRecord>()
    val putFiles = mutableListOf<PutFileRecord>()
    val moves = mutableListOf<Pair<String, String>>()
    val mkcols = mutableListOf<String>()

    /**
     * Every write request in issue order ("put:<path>", "putIfAbsent:<path>",
     * "putFile:<path>") — the record §10 ordering tests assert against.
     */
    val requests = mutableListOf<String>()

    data class PutRecord(val path: String, val text: String, val ifMatch: String?, val result: PutResult)

    data class PutFileRecord(val path: String, val bytes: ByteArray, val result: PutResult)

    /** Binary files served/stored separately from text (attachments/). */
    private val binaryFiles = LinkedHashMap<String, ByteArray>()

    fun seedFile(path: String, bytes: ByteArray) {
        binaryFiles[path] = bytes
    }

    fun fileBytes(path: String): ByteArray? = binaryFiles[path]

    fun seed(path: String, text: String, etag: String) {
        files[path] = text
        etags[path] = etag
    }

    fun text(path: String): String? = files[path]

    fun etag(path: String): String? = etags[path]

    fun snapshot(): Map<String, Pair<String, String?>> =
        files.entries.associate { (k, v) -> k to (v to etags[k]) }

    fun getCallCount(path: String): Int = getCalls[path] ?: 0

    fun successfulPutsTo(path: String): Int =
        puts.count { it.path == path && it.result is PutResult.WRITTEN }

    override fun list(dirPath: String): List<RemoteEntry> {
        // H4: the fake serves the live root AND the trash collection (Depth:1).
        val prefix = when (dirPath) {
            "", "/" -> ""
            SyncEngine.TRASH_DIR -> SyncEngine.TRASH_DIR + "/"
            else -> throw IllegalStateException("fake serves only root and ${SyncEngine.TRASH_DIR} listings")
        }
        authStatus?.let { throw com.piercingxx.xxnote.net.HttpError(it, "PROPFIND failed: HTTP $it") }
        if (!reachable) throw IOException("tailnet unreachable")
        return files.keys.asSequence()
            .filter { it.startsWith(prefix) && it.endsWith(".md") }
            .filter { it.substring(prefix.length).none { ch -> ch == '/' } } // Depth:1 only
            .sorted()
            .map { RemoteEntry(fileName = it.removePrefix(prefix), etag = etags[it], sizeBytes = null) }
            .toList()
    }

    override fun get(filePath: String): String? {
        getCalls.merge(filePath, 1, Int::plus)
        authStatus?.let { throw com.piercingxx.xxnote.net.HttpError(it, "GET failed: HTTP $it") }
        if (!reachable) throw IOException("tailnet unreachable")
        return files[filePath]
    }

    override fun put(filePath: String, wholeFileText: String, ifMatch: String?): PutResult {
        requests += "put:$filePath"
        var result = when {
            putMode == PutMode.ALWAYS_FAIL -> PutResult.FAILED
            putMode == PutMode.ALWAYS_412 -> PutResult.PRECONDITION_FAILED
            filePath in failNextPut412 -> {
                failNextPut412.remove(filePath)
                PutResult.PRECONDITION_FAILED
            }
            else -> PutResult.WRITTEN(null)
        }
        // A genuine If-Match mismatch rejects even when not scripted.
        if (result is PutResult.WRITTEN && ifMatch != null && etags[filePath] != ifMatch) {
            result = PutResult.PRECONDITION_FAILED
        }
        puts += PutRecord(filePath, wholeFileText, ifMatch, result)
        if (result !is PutResult.WRITTEN) return result
        files[filePath] = wholeFileText
        etagSeq++
        etags[filePath] = "\"e$etagSeq\""
        return PutResult.WRITTEN(echoedEtag(filePath))
    }

    override fun putIfAbsent(filePath: String, wholeFileText: String): PutResult {
        requests += "putIfAbsent:$filePath"
        val result = when {
            putMode == PutMode.ALWAYS_FAIL -> PutResult.FAILED
            putMode == PutMode.ALWAYS_412 || filePath in failNextPut412 -> {
                failNextPut412.remove(filePath)
                PutResult.PRECONDITION_FAILED
            }
            files.containsKey(filePath) -> PutResult.PRECONDITION_FAILED
            else -> PutResult.WRITTEN(null)
        }
        puts += PutRecord(filePath, wholeFileText, ifMatch = "*", result)
        if (result !is PutResult.WRITTEN) return result
        files[filePath] = wholeFileText
        etagSeq++
        etags[filePath] = "\"e$etagSeq\""
        return PutResult.WRITTEN(echoedEtag(filePath))
    }

    /** Scripted response ETag wins; unset paths echo the fresh stored one. */
    private fun echoedEtag(filePath: String): String? =
        if (scriptedPutEtags.containsKey(filePath)) scriptedPutEtags[filePath] else etags[filePath]

    override fun putFile(relativePath: String, bytes: ByteArray): PutResult {
        requests += "putFile:$relativePath"
        val result = when (putFileMode) {
            PutMode.ALWAYS_FAIL -> PutResult.FAILED
            PutMode.ALWAYS_412 -> PutResult.PRECONDITION_FAILED
            // Immutable content addresses: no If-Match is ever sent or checked.
            PutMode.NORMAL -> PutResult.WRITTEN(null)
        }
        putFiles += PutFileRecord(relativePath, bytes, result)
        if (result !is PutResult.WRITTEN) return result
        binaryFiles[relativePath] = bytes
        return result
    }

    override fun getFile(relativePath: String): ByteArray? {
        authStatus?.let { throw com.piercingxx.xxnote.net.HttpError(it, "GET failed: HTTP $it") }
        if (!reachable) throw IOException("tailnet unreachable")
        return binaryFiles[relativePath]
    }

    override fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean {
        moves += fromPath to toPath
        if (!reachable) return false
        // Overwrite: F — the server rejects the move rather than clobbering a
        // copy already at the destination (row 7 trash safety).
        if (!overwrite && files.containsKey(toPath)) return false
        val moved = files.remove(fromPath) ?: return false
        files[toPath] = moved
        etags[fromPath]?.let {
            etags.remove(fromPath)
            etags[toPath] = it
        }
        return true
    }

    override fun delete(filePath: String): Boolean {
        files.remove(filePath) ?: return false
        etags.remove(filePath)
        return true
    }

    override fun mkcol(dirPath: String): Boolean {
        mkcols += dirPath
        return true
    }
}

class InMemoryBook : SyncBookkeeping {

    val bases = LinkedHashMap<String, BaseSnapshot>()
    val logs = mutableListOf<SyncLogEntry>()

    private var opSeq = 0L
    private val ops = LinkedHashMap<Long, OutboxOp>()

    fun forgetAllBases() = bases.clear()

    override fun baseOf(noteId: String): BaseSnapshot? = bases[noteId]

    override fun recordBase(noteId: String, wholeFileText: String, etag: String?) {
        bases[noteId] = BaseSnapshot(body = wholeFileText, etag = etag)
    }

    override fun forgetBase(noteId: String) {
        bases.remove(noteId)
    }

    override fun log(entry: SyncLogEntry) {
        logs += entry
    }

    override fun enqueueOp(noteId: String, op: String, payload: String) {
        opSeq++
        ops[opSeq] = OutboxOp(
            id = opSeq,
            noteId = noteId,
            op = op,
            payload = payload,
            attempts = 0,
            lastError = null,
        )
    }

    override fun pendingOps(): List<OutboxOp> = ops.values.toList()

    override fun markOpDone(opId: Long) {
        ops.remove(opId)
    }

    override fun markOpFailed(opId: Long, error: String) {
        val current = ops[opId] ?: return
        ops[opId] = current.copy(attempts = current.attempts + 1, lastError = error)
    }
}

/**
 * In-memory [Attachments] for §10 ordering tests, mirroring AttachmentStore's
 * contract shape: rows keyed by full hash, lookups by 16-hex filename prefix,
 * `localBytes` reading "the store file" (here: the seeded array), and
 * `markRemoteKnown` flipping only the addressed row.
 */
class InMemoryAttachments : Attachments {

    class Row(val ext: String, val bytes: ByteArray?, var remoteKnown: Boolean)

    /** Full hash → row. */
    val rows = LinkedHashMap<String, Row>()

    fun seed(fullHash: String, ext: String, bytes: ByteArray, remoteKnown: Boolean = false) {
        rows[fullHash] = Row(ext, bytes, remoteKnown)
    }

    fun fileName(fullHash: String): String? =
        rows[fullHash]?.let { "${SyncEngine.ATTACHMENTS_DIR}${fullHash.take(16)}.${it.ext}" }

    override fun rowByPrefix(prefix16: String): AttachmentRow? {
        val p = prefix16.lowercase()
        return rows.entries.firstOrNull { it.key.take(16) == p }?.let { (hash, row) ->
            AttachmentRow(hash, row.ext, row.remoteKnown)
        }
    }

    override fun localBytes(prefix16: String): ByteArray? =
        rowByPrefix(prefix16)?.let { rows[it.hash]?.bytes }

    override fun markRemoteKnown(hash: String) {
        rows[hash]?.let { it.remoteKnown = true }
    }
}
