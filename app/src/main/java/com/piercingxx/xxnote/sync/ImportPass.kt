package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.Etag
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import java.io.IOException
import java.time.Instant

/**
 * §12's disclosed import, made real: the pass Setup's first sync runs BEFORE
 * [SyncEngine.syncOnce] so the ordinary pull that follows sees identified
 * files and lands them in the local vault.
 *
 * The promise being kept (§12, byte-for-byte in Setup's confirm step): every
 * listed `.md` "will be imported, ids assigned where missing, nothing will
 * be overwritten". Concretely, per visible `.md` in the live vault root:
 *
 * - A file whose frontmatter already carries a canonical ULID is NEVER
 *   rewritten — a second run over an imported vault touches nothing
 *   (idempotent by construction).
 * - Otherwise the file is re-stamped via [Frontmatter.rewritten] with a
 *   fresh ULID: unknown keys survive byte-exact in position and spelling,
 *   and a malformed block degrades to body text (R5 — nothing is discarded;
 *   a fresh well-formed block is prepended above the intact bytes).
 * - The stamped text goes back CONDITIONALLY, under `If-Match` with the
 *   ETag observed at listing time — if anyone changed the file since we saw
 *   it, the server answers 412 and the file is skipped and flagged for the
 *   next pass. Never overwritten.
 * - A listing entry WITHOUT an ETag — or with a weak one, which cannot lock
 *   a write (§4.2) — is refused-and-flagged without any request at all. The
 *   create-only escape hatch does not apply here: this file was just read,
 *   so it exists, and `If-None-Match` against it could only fail or — on a
 *   server sloppy enough to omit ETags — clobber. Refusal loses nothing.
 *
 * This pass deliberately lives OUTSIDE [SyncEngine]: assigning identity to
 * foreign files is Setup's disclosed import step (§12), never the engine's
 * silent move — the engine still flags id-less files "left unsynced".
 */
class ImportPass(
    private val remote: RemoteFiles,
    /** Injected like the engine's clock so JVM tests pin the ids. */
    private val clock: () -> Instant = { Instant.now() },
) {

    /** What one pass did, per visible `.md`, plus plain words for the screen. */
    data class Report(
        val seen: Int,
        val alreadyIdentified: Int,
        val stamped: Int,
        val raced: Int,
        val refusedNoLock: Int,
        val failed: Int,
        val vanished: Int,
        val listingFailed: Boolean = false,
    ) {
        /** Plain words for Setup's first-sync lines; silent when all went smoothly. */
        fun plainWords(): List<String> = buildList {
            if (listingFailed) {
                add("could not list the folder to import — first sync will retry")
                return@buildList
            }
            if (stamped > 0) add("gave ids to $stamped of $seen notes on import")
            if (alreadyIdentified == seen && seen > 0) add("all $seen notes already carry ids")
            if (raced > 0) {
                add("$raced changed while we were reading ${if (raced == 1) "it" else "them"} — left untouched for the next sync")
            }
            if (refusedNoLock > 0) {
                add("$refusedNoLock have no lockable ETag — left unsynced rather than written blind")
            }
            if (failed > 0) add("$failed could not be written just now — the next sync retries")
            if (vanished > 0) add("$vanished vanished between listing and reading — left alone")
        }
    }

    fun run(dirPath: String = SyncEngine.VAULT_ROOT): Report {
        val entries = try {
            remote.list(dirPath)
        } catch (_: IOException) {
            return Report(0, 0, 0, 0, 0, 0, 0, listingFailed = true)
        }
        val candidates = entries
            .filter { entry ->
                val name = entry.fileName
                name.endsWith(SyncEngine.MD_SUFFIX) &&
                    !name.startsWith(".") &&
                    !name.startsWith(SyncEngine.ATTACHMENTS_DIR)
            }
            .sortedBy { it.fileName }

        var alreadyIdentified = 0
        var stamped = 0
        var raced = 0
        var refusedNoLock = 0
        var failed = 0
        var vanished = 0

        for (entry in candidates) {
            val path = if (dirPath.isEmpty()) entry.fileName else "$dirPath/${entry.fileName}"
            val text = try {
                remote.get(path)
            } catch (_: IOException) {
                failed++
                continue
            }
            if (text == null) {
                vanished++
                continue
            }
            if (Frontmatter.parse(text).id?.takeIf(Ulid::isValid) != null) {
                alreadyIdentified++
                continue
            }

            val stampedText = Frontmatter.parse(text).rewritten {
                id = Ulid.generateAt(clock().toEpochMilli())
            }
            val result =
                if (Etag.isStrong(entry.etag)) remote.put(path, stampedText, entry.etag)
                else PutResult.Refused(NO_LOCK_WORDS)
            when (result) {
                is PutResult.WRITTEN -> stamped++
                PutResult.PRECONDITION_FAILED -> raced++
                is PutResult.Refused -> refusedNoLock++
                PutResult.FAILED -> failed++
            }
        }
        return Report(
            seen = candidates.size,
            alreadyIdentified = alreadyIdentified,
            stamped = stamped,
            raced = raced,
            refusedNoLock = refusedNoLock,
            failed = failed,
            vanished = vanished,
        )
    }

    private companion object {
        const val NO_LOCK_WORDS =
            "no usable ETag to lock the write — the file was left as found"
    }
}
