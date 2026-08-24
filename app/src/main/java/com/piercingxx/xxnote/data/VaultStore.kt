package com.piercingxx.xxnote.data

import android.content.Context
import com.piercingxx.xxnote.core.BaseSnapshot
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.FrontmatterDocument
import com.piercingxx.xxnote.core.NoteType
import com.piercingxx.xxnote.core.Slug
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.sync.LocalFiles
import com.piercingxx.xxnote.sync.LocalNote
import com.piercingxx.xxnote.sync.OutboxOp
import com.piercingxx.xxnote.sync.SyncBookkeeping
import com.piercingxx.xxnote.sync.SyncLogEntry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * H3: raised by [VaultStore.write] when the target note rests in trash — an
 * [IllegalStateException] (the write is in an impossible state) with a
 * dedicated type so callers can degrade gracefully instead of guessing from
 * the message.
 */
class TrashedNoteException(message: String) : IllegalStateException(message)

/**
 * The near side of the sync engine (§5): the `filesDir/vault` mirror (D14)
 * backed by Room as cache + outbox. Implements [LocalFiles] and
 * [SyncBookkeeping]; WebDavClient implements the far side.
 *
 * Laws this class enforces:
 * - The vault file is truth; a Room row is rebuilt from the file whenever they
 *   disagree (D1). [read]/[listLive] re-read from disk, never from `note.body`.
 * - Identity is the frontmatter `id`, never the path (D3/R7) — a file renamed
 *   on disk is found, its row's path rebuilt (§9).
 * - Every mirror write is temp-then-rename (§15): a failed write leaves the
 *   previous bytes intact and no `.tmp` residue behind.
 * - Trash is a move to `.xxnote/trash/` with `trashedAt:` stamped via
 *   [FrontmatterDocument.rewritten]; restore reverses it. Never unlink (D9).
 *
 * The ports are synchronous by design (engine fakes implement them too), so
 * Room calls run inside [runBlocking]; callers are engine coroutines or test
 * threads, never the main thread.
 */
class VaultStore internal constructor(
    private val mirrorRoot: File,
    private val db: XxDatabase,
) : LocalFiles, SyncBookkeeping {

    /** Production wiring: the process-wide db (hardening #9), mirror at filesDir/vault. */
    constructor(context: Context) : this(
        mirrorRoot = File(context.filesDir, MIRROR_DIR),
        db = XxDatabase.getInstance(context),
    )

    // ---- LocalFiles ---------------------------------------------------------

    override fun listLive(): List<LocalNote> = scan(live = true)

    override fun listTrashed(): List<LocalNote> = scan(live = false)

    /**
     * By frontmatter `id`, never by path. Fast-path reads the row's recorded
     * location; on a miss (the file was renamed/moved since the last scan) the
     * mirror is rescanned — which rebuilds moved rows — and the read retried.
     */
    override fun read(id: String): LocalNote? {
        readRow(id)?.let { return it }
        scan(live = true)
        readRow(id)?.let { return it }
        scan(live = false)
        return readRow(id)
    }

    /**
     * Write-through: temp-then-rename onto the mirror, then the Room row is
     * rebuilt from the bytes just written (file wins). If [wholeFileText]
     * carries no `id`, [id] is stamped in first (the §12 import write); if it
     * carries a different id, this fails — an identity-keyed API must not
     * silently relocate another note's identity.
     */
    override fun write(id: String, wholeFileText: String) {
        var text = wholeFileText
        var doc = Frontmatter.parse(text)
        if (doc.id == null) {
            text = doc.rewritten { this.id = id }
            doc = Frontmatter.parse(text)
        }
        check(doc.id == id) {
            "write($id): text carries id ${doc.id} — identity mismatch (D3)"
        }
        val previous = runBlocking { db.noteDao().byId(id) }
        if (previous != null && isTrashPath(previous.path)) {
            // H3: a pending editor save landed after a batch-trash. Writing
            // here would mint a live twin beside the trash copy (same id in
            // two places). Refuse; the caller degrades gracefully and the
            // trashed bytes stay untouched.
            throw TrashedNoteException("note is trashed; restore before writing")
        }
        var relPath = previous?.path ?: basePathFor(id, doc.title ?: "")
        val occupiedBy = File(mirrorRoot, relPath)
            .takeIf { it.isFile }?.let { Frontmatter.parse(it.readText()).id }
        if (occupiedBy != null && occupiedBy != id) {
            relPath = firstFree(mirrorRoot, relPath)
        }
        atomicWrite(File(mirrorRoot, relPath), text)
        rebuildRow(id, relPath, text, live = true, previous = previous)
    }

    /** Stamp `updated:` + `trashedAt:`, move the file to `.xxnote/trash/` (D9, §9). */
    override fun trash(id: String) {
        val row = runBlocking { db.noteDao().byId(id) }
            ?: throw IllegalArgumentException("trash: unknown note $id")
        if (isTrashPath(row.path)) return
        val source = File(mirrorRoot, row.path)
        val text = if (source.isFile) source.readText() else row.body
        val stamped = Frontmatter.parse(text).rewritten {
            updated = nowIso()
            trashedAt = nowIso()
        }
        val trashDir = File(mirrorRoot, TRASH_DIR)
        trashDir.mkdirs()
        val name = firstFree(trashDir, source.name)
        atomicWrite(File(trashDir, name), stamped)
        if (source.exists()) source.delete()
        rebuildRow(id, "$TRASH_DIR/$name", stamped, live = false, previous = row)
    }

    /** Reverse of [trash]: strip `trashedAt:`, move back to the vault root. */
    override fun restore(id: String) {
        val row = runBlocking { db.noteDao().byId(id) }
            ?: throw IllegalArgumentException("restore: unknown note $id")
        require(isTrashPath(row.path)) { "restore: $id is not trashed" }
        val source = File(mirrorRoot, row.path)
        val text = if (source.isFile) source.readText() else row.body
        val restored = Frontmatter.parse(text).rewritten { trashedAt = null }
        val relPath = firstFree(mirrorRoot, File(row.path).name)
        atomicWrite(File(mirrorRoot, relPath), restored)
        if (source.exists()) source.delete()
        rebuildRow(id, relPath, restored, live = true, previous = row)
    }

    /**
     * Row 8 fork-of-trash (Ports.kt): re-stamps the trashed note in place —
     * same trash path, fresh identity via [stamp] — and retires the old Room
     * row so exactly one row keys the file afterwards. The bytes never leave
     * trash and are never unlinked.
     */
    override fun forkTrashedCopy(id: String, newId: String, stamp: (String) -> String) {
        val row = runBlocking { db.noteDao().byId(id) }
            ?: throw IllegalArgumentException("forkTrashedCopy: unknown note $id")
        check(isTrashPath(row.path)) { "forkTrashedCopy: $id is not trashed" }
        val source = File(mirrorRoot, row.path)
        val text = if (source.isFile) source.readText() else row.body
        val stamped = stamp(text)
        check(Frontmatter.parse(stamped).id == newId) {
            "forkTrashedCopy($id → $newId): stamp did not apply the new identity"
        }
        atomicWrite(source, stamped)
        runBlocking { db.noteDao().delete(id) }
        rebuildRow(newId, row.path, stamped, live = false, previous = null)
    }

    /** `<ulid>-<slug>.md` for a fresh note (§8). */
    override fun basePathFor(id: String, title: String): String =
        "$id-${Slug.of(title)}.md"

    /**
     * D9 expiry sweep, local half: for every trashed row whose MIRROR FILE's
     * frontmatter carries a parsable `trashedAt:` strictly older than
     * [now] - [olderThanMs], delete the mirror file AND its Room row. A file
     * without a parsable stamp is NEVER deleted here (belt-and-braces with
     * SyncEngine's own assertion — expiry never destroys unstamped bytes).
     * Returns the number of entries purged.
     */
    override fun purgeExpiredTrash(olderThanMs: Long, now: Long): Int {
        var purged = 0
        for (row in runBlocking { db.noteDao().listTrashedRows() }) {
            if (!isTrashPath(row.path)) continue
            val file = File(mirrorRoot, row.path)
            val text = if (file.isFile) file.readText() else row.body
            val stampMillis = Frontmatter.parse(text).trashedAt?.let { isoToMillis(it) }
            if (stampMillis == null) continue // no stamp → never expires by this sweep
            if (now - stampMillis <= olderThanMs) continue // still inside retention
            if (file.exists()) file.delete()
            runBlocking { db.noteDao().delete(row.id) }
            purged++
        }
        return purged
    }

    /** First unused variant of [base] (`_1`, `_2`, …) against the vault root — forks (§7). */
    override fun freeName(base: String): String = firstFree(mirrorRoot, base)

    // ---- Scan / reconcile ---------------------------------------------------

    /**
     * Walks the mirror (or the trash folder), parses each `.md`, reconciles
     * every row against what is on disk, and returns what was found.
     *
     * ANY non-blank `id:` value is identity — ULID-shaped or not; the file's
     * bytes must survive a scan untouched. Only a file with no id at all gets
     * one assigned and written back — the only unsolicited write XX-Note
     * performs (§12 Import, §15). Two files claiming one `id` inside one scan
     * keep the first (sorted); forking the newer is §6 row 11 and belongs to
     * SyncEngine, not here.
     */
    private fun scan(live: Boolean): List<LocalNote> {
        val notes = ArrayList<LocalNote>()
        val seenIds = HashSet<String>()
        for (file in mdFiles(live)) {
            var text = file.readText()
            var doc = Frontmatter.parse(text)
            val declared = doc.id?.takeIf { it.isNotBlank() }
            val id: String
            if (declared == null) {
                if (!live) continue
                val fresh = Ulid.generate()
                text = doc.rewritten { this.id = fresh }
                doc = Frontmatter.parse(text)
                atomicWrite(file, text)
                id = fresh
            } else {
                id = declared
            }
            if (!seenIds.add(id)) continue
            val relPath = if (live) {
                file.toRelativeString(mirrorRoot).replace(File.separatorChar, '/')
            } else {
                "$TRASH_DIR/${file.name}"
            }
            val previous = runBlocking { db.noteDao().byId(id) }
            rebuildRow(id, relPath, text, live = live, previous = previous)
            notes.add(LocalNote(id = id, path = relPath, wholeFileText = text, trashed = !live))
        }
        return notes
    }

    /** Recursive `.md` collection; skips `.xxnote/` everywhere and root `attachments/`. */
    private fun mdFiles(live: Boolean): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, isRoot: Boolean) {
            val entries = dir.listFiles() ?: return
            for (entry in entries.sortedBy { it.name }) {
                when {
                    entry.isDirectory -> {
                        if (entry.name == DOT_DIR) continue
                        if (isRoot && entry.name == AttachmentStore.ATTACHMENTS_DIR) continue
                        walk(entry, isRoot = false)
                    }
                    entry.isFile &&
                        entry.name.endsWith(".md") &&
                        !entry.name.endsWith(TMP_SUFFIX) -> out.add(entry)
                }
            }
        }
        walk(if (live) mirrorRoot else File(mirrorRoot, TRASH_DIR), isRoot = live)
        return out
    }

    /** Read through one row's current path; rebuilds the row when the file differs. */
    private fun readRow(id: String): LocalNote? {
        val row = runBlocking { db.noteDao().byId(id) } ?: return null
        val file = File(mirrorRoot, row.path)
        if (!file.isFile) return null
        val text = file.readText()
        if (Frontmatter.parse(text).id != id) return null
        val live = !isTrashPath(row.path)
        rebuildRow(id, row.path, text, live = live, previous = row)
        return LocalNote(id = id, path = row.path, wholeFileText = text, trashed = !live)
    }

    // ---- Row rebuilding (file wins, D1) --------------------------------------

    private fun rebuildRow(
        id: String,
        relPath: String,
        text: String,
        live: Boolean,
        previous: NoteEntity?,
    ) {
        val doc = Frontmatter.parse(text)
        val fileMtime = File(mirrorRoot, relPath).let { if (it.isFile) it.lastModified() else 0L }
        val created = isoToMillis(doc.created)
            ?: previous?.created
            ?: fileMtime.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val updated = isoToMillis(doc.updated)
            ?: previous?.updated
            ?: created
        val row = NoteEntity(
            id = id,
            path = relPath,
            title = doc.title ?: "",
            body = text,
            created = created,
            updated = updated,
            pinned = doc.pinned ?: false,
            archived = doc.archived ?: false,
            color = doc.color,
            type = if (doc.type == NoteType.CHECKLIST) "checklist" else "note",
            trashedAt = if (live) null else (isoToMillis(doc.trashedAt) ?: System.currentTimeMillis()),
            conflictOf = doc.conflictOf,
            extraFrontmatter = extraFrontmatterOf(doc),
        )
        runBlocking { db.noteDao().upsert(row) }
    }

    /**
     * Verbatim unknown-key lines from the frontmatter block, `\n`-joined, so
     * Obsidian-plugin metadata is visible to later workstreams without being
     * owned (§4.3). Null when there is nothing extra. Owned keys never appear.
     */
    private fun extraFrontmatterOf(doc: FrontmatterDocument): String? {
        if (!doc.hasFrontmatter || doc.isMalformed) return null
        val blockEnd = doc.raw().length - doc.bodyText.length
        val extras = ArrayList<String>()
        for (rawLine in doc.raw().substring(0, blockEnd).split('\n').drop(1)) {
            val line = rawLine.removeSuffix("\r")
            val trimmed = line.trim()
            if (trimmed == "---" || trimmed == "...") break
            val key = KEY_LINE.matchEntire(line)?.groupValues?.get(1) ?: continue
            if (key.trim() !in OWNED_KEYS) extras.add(line)
        }
        return extras.joinToString("\n").ifEmpty { null }
    }

    // ---- Mirror I/O (temp-then-rename, §15) -----------------------------------

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        fsyncDir(target.parentFile ?: return)
    }

    /**
     * Hardening #12: the rename is only durable once its parent directory
     * entry is, so fsync the directory too. Best-effort BY DESIGN — some
     * platforms or filesystems refuse to open/sync a directory fd, and a
     * durability nicety must never fail an already-committed write (the temp
     * file's own fd.sync() above still bounds the loss). Swallowed silently:
     * this codebase logs nothing.
     */
    private fun fsyncDir(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Directory fsync unavailable on this platform; degrade to no-op.
        }
    }

    /** First unused `<stem>_<n><ext>` variant of [baseName] inside [dir]. */
    private fun firstFree(dir: File, baseName: String): String {
        var n = 1
        var candidate = baseName
        while (File(dir, candidate).exists()) {
            candidate = variantOf(baseName, n)
            n++
        }
        return candidate
    }

    private fun variantOf(base: String, n: Int): String {
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) + "_$n" + base.substring(dot) else "${base}_$n"
    }

    private fun isTrashPath(relPath: String): Boolean = relPath.startsWith("$TRASH_DIR/")

    // ---- SyncBookkeeping -------------------------------------------------------

    override fun baseOf(noteId: String): BaseSnapshot? = runBlocking {
        db.baseSnapshotDao().byId(noteId)?.let { BaseSnapshot(body = it.body, etag = it.etag) }
    }

    override fun recordBase(noteId: String, wholeFileText: String, etag: String?) = runBlocking {
        val doc = Frontmatter.parse(wholeFileText)
        val fmLength = if (doc.hasFrontmatter) wholeFileText.length - doc.bodyText.length else 0
        val now = System.currentTimeMillis()
        db.baseSnapshotDao().upsert(
            BaseSnapshotEntity(
                id = noteId,
                body = wholeFileText,
                frontmatter = if (fmLength > 0) wholeFileText.substring(0, fmLength) else "",
                etag = etag,
                remoteMtime = now,
                syncedAt = now,
            ),
        )
        Unit
    }

    override fun forgetBase(noteId: String) = runBlocking {
        db.baseSnapshotDao().delete(noteId)
        Unit
    }

    override fun log(entry: SyncLogEntry) = runBlocking {
        db.syncLogDao().insert(
            SyncLogEntity(
                at = System.currentTimeMillis(),
                noteId = entry.noteId,
                verdict = entry.verdict,
                reason = entry.reason,
                ok = entry.ok,
                detail = entry.detail,
            ),
        )
        db.syncLogDao().pruneToCap()
        Unit
    }

    override fun enqueueOp(noteId: String, op: String, payload: String) = runBlocking {
        require(op in OPS) { "unknown outbox op '$op' ($OPS_MESSAGE)" }
        db.outboxDao().insert(
            OutboxEntity(
                noteId = noteId,
                op = op,
                payload = payload,
                queuedAt = System.currentTimeMillis(),
            ),
        )
        Unit
    }

    override fun pendingOps(): List<OutboxOp> = runBlocking {
        db.outboxDao().pending().map {
            OutboxOp(it.id, it.noteId, it.op, it.payload, it.attempts, it.lastError)
        }
    }

    override fun markOpDone(opId: Long) = runBlocking {
        db.outboxDao().markDone(opId)
        Unit
    }

    override fun markOpFailed(opId: Long, error: String) = runBlocking {
        val op = db.outboxDao().byId(opId)
        if (op != null && op.attempts + 1 >= MAX_OP_ATTEMPTS) {
            // Zombie cap: an op failing this many times is abandoned, not
            // retried forever. The durable intent survives in base-vs-mirror
            // divergence — the next sync re-decides the note from scratch.
            db.outboxDao().markDone(opId)
            db.syncLogDao().insert(
                SyncLogEntity(
                    at = System.currentTimeMillis(),
                    noteId = op.noteId,
                    verdict = "Outbox",
                    reason = "op ${op.op} abandoned after $MAX_OP_ATTEMPTS attempts: $error",
                    ok = false,
                    detail = null,
                ),
            )
        } else {
            db.outboxDao().markOpFailed(opId, error)
        }
        Unit
    }

    companion object {
        const val MIRROR_DIR = "vault"
        const val DOT_DIR = ".xxnote"
        const val TRASH_DIR = ".xxnote/trash"
        const val TMP_SUFFIX = ".tmp"

        /** An outbox op failing this many times is purged, not retried forever (§15). */
        const val MAX_OP_ATTEMPTS = 5

        private val OWNED_KEYS = setOf(
            "id", "title", "created", "updated", "pinned", "archived", "color",
            "labels", "type", "reminder", "conflictOf", "conflictAt", "trashedAt",
        )
        private val KEY_LINE = Regex("""\s*([A-Za-z][A-Za-z0-9_-]*)\s*:.*""")

        private val OPS = setOf("put", "move", "trash", "delete", "attach")
        private const val OPS_MESSAGE = "expected one of 'put'|'move'|'trash'|'delete'|'attach'"

        private fun nowIso(): String =
            Instant.ofEpochMilli(System.currentTimeMillis()).toString()

        private fun isoToMillis(value: String?): Long? = try {
            value?.let { Instant.parse(it).toEpochMilli() }
        } catch (_: Exception) {
            null
        }
    }
}
