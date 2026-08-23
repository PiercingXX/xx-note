package com.piercingxx.xxnote.data

import com.piercingxx.xxnote.net.WebDavClient
import com.piercingxx.xxnote.util.ExifStripper
import com.piercingxx.xxnote.util.Heic
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/**
 * The attachment pipeline (D13, §10): content-addressed, immutable, deduped.
 *
 * ## Insert pipeline ([insert])
 * 1. [Heic.transcode] — HEIC/HEIF in, JPEG out (R11: readable by anything).
 * 2. Write to a temp file inside `attachments/` (ExifInterface needs a seekable).
 * 3. [ExifStripper.strip] — GPS block removed unconditionally (§10); the hash
 *    is computed over the STRIPPED bytes, so no location data ever reaches
 *    disk under its final name, Room, or the NAS.
 * 4. SHA-256 → `attachments/<first-16-hex>.<ext>` (temp-then-rename, §15).
 * 5. Dedup by full hash: an existing row returns as-is; this call's bytes are
 *    dropped. A dedup hit whose local file was evicted is re-cached for free
 *    (the bytes are already in hand) by restoring `localPath`.
 * 6. Row recorded with byte count, dimensions, `lastViewedAt=now`,
 *    `remoteKnown=false`. Upload itself is the note push's job and must land
 *    before the body that references it — ordering lives in the engine, which
 *    reads [AttachmentEntity.remoteKnown] / the row written here.
 *
 * ## References and orphans
 * Notes reference attachments as Markdown image links
 * `![](attachments/<16-hex>.<ext>)`; any Markdown reader resolves them (R11).
 * [referencedHashes] extracts those 16-hex tokens from WHOLE-FILE texts;
 * [orphanCount] reports stored-but-unreferenced rows; [sweepOrphans] removes
 * their local rows + files only. The remote copy is NEVER deleted here: a
 * reference may live in a note this device has not pulled yet (§10). Sweeping
 * is a manual one-tap action on the sync screen; it is never automatic.
 *
 * ## Local cache
 * The vault on the NAS is truth (D1/R3). The local file is a cache:
 * [ensureLocal] lazily downloads on first view, [touch] records views, and
 * [evictToBudget] sheds least-recently-viewed files down to the budget while
 * keeping every row (eviction deletes the local copy only).
 *
 * Nothing here ever logs — not statuses, not paths, never bytes.
 *
 * Concurrency: instances are stateless apart from [clock]; all persistence
 * goes through Room (serialized per database) and temp-then-rename writes,
 * so two inserts of the same photo converge on one file.
 */
class AttachmentStore(
    private val vaultRoot: File,
    private val dao: AttachmentDao,
    private val clientProvider: (() -> WebDavClient)? = null,
) : com.piercingxx.xxnote.sync.Attachments {

    /** Outcome of [insert]; [relativePath] drops straight into a Markdown link. */
    data class InsertResult(
        /** Full 64-hex SHA-256 of the stored (stripped/transcoded) bytes. */
        val hash: String,
        /** Vault-relative path: `attachments/<first-16-hex>.<ext>`. */
        val relativePath: String,
        /** Stored image dimensions; 0 when unknown (non-decodable passthrough). */
        val width: Int,
        val height: Int,
        /** True when THIS call re-encoded HEIC/HEIF → JPEG. */
        val transcoded: Boolean,
    )

    /**
     * Deterministic-time seam (tests inject a fixed clock). Production uses
     * the wall clock; nothing here trusts timestamps for correctness beyond
     * LRU ordering, which is explicitly allowed to be approximate.
     */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Full insert pipeline over camera/gallery bytes. See class KDoc.
     *
     * @throws IOException when writing or EXIF-stripping fails — a failed
     *   strip aborts the insert (never store unstripped location data).
     */
    @Throws(IOException::class)
    suspend fun insert(bytes: ByteArray, ext: String, mimeType: String?): InsertResult {
        val t = Heic.transcode(bytes, ext, mimeType)
        val incoming = File(dir().apply { mkdirs() }, "incoming-${System.nanoTime()}$TMP_SUFFIX")
        try {
            atomicWrite(incoming, t.bytes)
            ExifStripper.strip(incoming.absolutePath)
            val stripped = incoming.readBytes()
            return insertProcessed(stripped, t.ext, t.width, t.height, transcodedThisCall = t.transcoded)
        } finally {
            incoming.delete()
        }
    }

    /**
     * Hash→dedup→store→record, from already-processed bytes. This is the pure
     * seam behind [insert]: identical logic without HEIC decoding or EXIF
     * rewriting, so the addressing/dedup/path math runs on the JVM with fake
     * DAOs and no bitmaps. Not part of the app-facing API surface.
     */
    internal suspend fun insertProcessed(
        finalBytes: ByteArray,
        ext: String,
        width: Int,
        height: Int,
        transcodedThisCall: Boolean = false,
    ): InsertResult {
        requireSafeExt(ext)
        val hash = sha256Hex(finalBytes)
        val prefix = hash.take(FILE_HEX_PREFIX)
        val existing = dao.byHash(hash)
        if (existing != null) {
            val relPath = relPath(prefix, existing.ext)
            // Free cache-fill: evicted dedup hits come back with zero upload.
            if (!File(vaultRoot, relPath).isFile) {
                atomicWrite(File(vaultRoot, relPath), finalBytes)
                dao.upsert(existing.copy(localPath = relPath))
            }
            return InsertResult(hash, relPath, existing.w, existing.h, transcodedThisCall)
        }
        require(width >= 0 && height >= 0) { "negative dimensions" }
        val relPath = relPath(prefix, Heic.normalizeExt(ext))
        atomicWrite(File(vaultRoot, relPath), finalBytes)
        dao.upsert(
            AttachmentEntity(
                hash = hash,
                ext = Heic.normalizeExt(ext),
                bytes = finalBytes.size.toLong(),
                w = width,
                h = height,
                localPath = relPath,
                lastViewedAt = clock(),
                remoteKnown = false,
            ),
        )
        return InsertResult(hash, relPath, width, height, transcodedThisCall)
    }

    /**
     * Scans WHOLE-FILE texts (entire `.md` files, frontmatter included) for
     * attachment references — `attachments/<16-hex>.<ext>` — and returns the
     * normalized lowercase 16-hex tokens. Pure regex; matches inside Markdown
     * image links, plain links, and HTML `src` alike (over-matching is safe:
     * it only makes orphan sweeping more conservative). Non-image links and
     * anything without the content-address shape are ignored.
     *
     * The regex itself lives in [com.piercingxx.xxnote.sync.AttachmentRefs]
     * so the sync engine's upload-before-body ordering (§10) sweeps with the
     * exact same notion of "referenced".
     */
    fun referencedHashes(allWholeFileTexts: List<String>): Set<String> =
        com.piercingxx.xxnote.sync.AttachmentRefs.hashes(allWholeFileTexts)

    /**
     * How many stored rows no provided text references — the sync screen's
     * report-only number (§10). Never acts; see [sweepOrphans].
     */
    suspend fun orphanCount(allWholeFileTexts: List<String>): Int {
        val refs = referencedHashes(allWholeFileTexts)
        return dao.all().count { it.hash.take(FILE_HEX_PREFIX) !in refs }
    }

    /**
     * Deletes LOCAL rows + files whose hash none of [allWholeFileTexts]
     * reference. Remote copies are untouched — the reference may live in an
     * unpulled note (§10), so only the operator's explicit one-tap sweep
     * calls this. Returns how many orphans were removed.
     */
    suspend fun sweepOrphans(allWholeFileTexts: List<String>): Int {
        val refs = referencedHashes(allWholeFileTexts)
        var swept = 0
        for (row in dao.all()) {
            if (row.hash.take(FILE_HEX_PREFIX) in refs) continue
            File(vaultRoot, relPath(row.hash.take(FILE_HEX_PREFIX), row.ext)).delete()
            dao.delete(row.hash)
            swept++
        }
        return swept
    }

    /**
     * Records a view of [hash] (grid render, editor open) — the recency input
     * to [evictToBudget]. Unknown hashes are ignored.
     */
    suspend fun touch(hash: String) {
        val row = dao.byHash(hash) ?: return
        dao.upsert(row.copy(lastViewedAt = clock()))
    }

    /**
     * Sheds local copies until the cached total fits [budgetBytes], least-
     * recently-viewed first (`lastViewedAt` ASC, hash ASC as the deterministic
     * tie-break). Rows survive eviction with `localPath = null` — the remote
     * is truth and the next view re-downloads via [ensureLocal]. Rows whose
     * file already vanished count as zero and just lose their stale path.
     */
    suspend fun evictToBudget(budgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES) {
        if (budgetBytes < 0) return
        val candidates = dao.all()
            .asSequence()
            .filter { it.localPath != null }
            .sortedWith(compareBy({ it.lastViewedAt }, { it.hash }))
            .toList()
        var total = candidates.sumOf { row -> localSizeOf(row) }
        for (row in candidates) {
            val relPath = relPath(row.hash.take(FILE_HEX_PREFIX), row.ext)
            val size = localSizeOf(row)
            if (size == 0L) {
                // File already vanished: drop the stale pointer — free, and
                // it keeps rows honest about what they actually hold.
                dao.upsert(row.copy(localPath = null))
                continue
            }
            if (total <= budgetBytes) break
            val gone = File(vaultRoot, relPath).delete()
            if (!gone && File(vaultRoot, relPath).isFile) continue // undeletable: keep accounting honest
            total -= size
            dao.upsert(row.copy(localPath = null))
        }
    }

    /**
     * Lazy download (§10): returns the local file for [hash], fetching
     * `attachments/<hash-prefix>.<ext>` from the configured WebDAV origin when
     * absent. Null when there is no client, the remote lacks the file, the
     * transport fails, or — never silently tolerated — the fetched bytes do
     * not hash to [hash] (content addressing makes corruption detectable, and
     * wrong bytes must never be stored under a right address). On success the
     * row gains its `localPath`, `remoteKnown=true`, and a fresh view time.
     * An unknown hash gets a placeholder row (w/h = 0) so every local file
     * always has a row — sweepOrphans' invariant.
     *
     * THE LAZY-DOWNLOAD HOOK for image rendering, which is deliberately not
     * wired to any UI yet (no grid thumbnails / editor image composables exist
     * in v1): when rendering lands, each visible attachment calls
     * [touch] + [ensureLocal] — touch first so LRU recency is honest even if
     * the fetch fails. The contract is proven by AttachmentStoreTest's
     * ensureLocal block: absent → GET → cached (absentFileDownloads…),
     * corrupt bytes → null and nothing stored (corruptedDownloadIsRefused…).
     */
    suspend fun ensureLocal(hash: String, ext: String): File? {
        requireHashShape(hash)
        requireSafeExt(ext)
        val prefix = hash.lowercase().take(FILE_HEX_PREFIX)
        val relPath = relPath(prefix, Heic.normalizeExt(ext))
        val local = File(vaultRoot, relPath)
        if (local.isFile) return local
        val provider = clientProvider ?: return null
        val bytes = try {
            provider().getFile(relPath)
        } catch (_: IOException) {
            null
        } ?: return null
        if (sha256Hex(bytes) != hash.lowercase()) return null
        atomicWrite(local, bytes)
        val now = clock()
        val row = dao.byHash(hash)
        dao.upsert(
            row?.copy(localPath = relPath, lastViewedAt = now, remoteKnown = true)
                ?: AttachmentEntity(
                    hash = hash.lowercase(),
                    ext = Heic.normalizeExt(ext),
                    bytes = bytes.size.toLong(),
                    w = 0,
                    h = 0,
                    localPath = relPath,
                    lastViewedAt = now,
                    remoteKnown = true,
                ),
        )
        return local
    }

    /**
     * Records that a copy of [hash] is confirmed on the far side. Called by
     * the sync engine right after its §10 upload lands (upload-before-body);
     * unknown hashes are ignored.
     */
    override fun markRemoteKnown(hash: String) {
        val row = runBlocking { dao.byHash(hash) } ?: return
        if (row.remoteKnown) return
        runBlocking { dao.upsert(row.copy(remoteKnown = true)) }
    }

    // --- sync.Attachments port (§10 upload ordering) -------------------------
    // Synchronous like every engine port — Room rides runBlocking inside, per
    // the VaultStore precedent. Read/mark only: the engine never writes rows
    // or files through this seam.

    override fun rowByPrefix(prefix16: String): com.piercingxx.xxnote.sync.AttachmentRow? {
        val p = prefix16.lowercase()
        return runBlocking { dao.all() }
            .firstOrNull { it.hash.take(FILE_HEX_PREFIX) == p }
            ?.let { com.piercingxx.xxnote.sync.AttachmentRow(it.hash, it.ext, it.remoteKnown) }
    }

    override fun localBytes(prefix16: String): ByteArray? {
        val p = prefix16.lowercase()
        return runBlocking { dao.all() }
            .firstOrNull { it.hash.take(FILE_HEX_PREFIX) == p }
            ?.let { row -> File(vaultRoot, relPath(p, row.ext)).takeIf(File::isFile)?.readBytes() }
    }

    // --- plumbing ---------------------------------------------------------

    private fun dir(): File = File(vaultRoot, ATTACHMENTS_DIR)

    private fun relPath(prefix16: String, ext: String): String =
        "$ATTACHMENTS_DIR/$prefix16.$ext"

    /** Bytes attributed to a cached row; a missing file counts as zero. */
    private fun localSizeOf(row: AttachmentEntity): Long =
        if (File(vaultRoot, relPath(row.hash.take(FILE_HEX_PREFIX), row.ext)).isFile) row.bytes else 0L

    private fun requireSafeExt(ext: String) {
        require(
            ext.isNotBlank() &&
                !ext.contains('/') &&
                !ext.contains('\\') &&
                !ext.contains('.'),
        ) { "invalid attachment extension: '$ext'" }
    }

    private fun requireHashShape(hash: String) {
        require(HASH_SHAPE.matches(hash)) { "invalid attachment hash: '${hash.take(8)}…'" }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        const val ATTACHMENTS_DIR = "attachments"
        const val TMP_SUFFIX = ".tmp"

        /** §10: filenames carry the first 16 hex chars of the SHA-256. */
        const val FILE_HEX_PREFIX = 16

        /** Default local cache budget: 500 MB least-recently-viewed (§10). */
        const val DEFAULT_CACHE_BUDGET_BYTES: Long = 500L * 1024 * 1024

        private val HASH_SHAPE = Regex("[0-9a-fA-F]{64}")

        /**
         * The reference shape notes use: `attachments/<exactly-16-hex>.<ext>`.
         * Longer/shorter hex does not match (the literal anchor plus exact run
         * of 16 followed by `.` prevents partial hits inside longer names).
         * Kept as an alias of the shared [com.piercingxx.xxnote.sync.AttachmentRefs]
         * pattern — one regex, one truth.
         */
        internal val ATTACHMENT_LINK = com.piercingxx.xxnote.sync.AttachmentRefs.PATTERN

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
