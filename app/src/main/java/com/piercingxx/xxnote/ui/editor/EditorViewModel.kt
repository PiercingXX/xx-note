package com.piercingxx.xxnote.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.NoteType
import com.piercingxx.xxnote.data.AttachmentStore
import com.piercingxx.xxnote.data.TrashedNoteException
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.sync.MergeEngine
import com.piercingxx.xxnote.sync.SyncWorker
import com.piercingxx.xxnote.ui.grid.canonicalColorFor
import com.piercingxx.xxnote.ui.grid.toneForColor
import com.piercingxx.xxnote.ui.labels.LabelIntent
import com.piercingxx.xxnote.ui.labels.LabelOps
import com.piercingxx.xxnote.ui.labels.foldLabelIntents
import com.piercingxx.xxnote.core.Ulid
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Editor state holder (design §12 item 2): authoritative title/body strings,
 * the 800 ms debounced save, and the colour-tone write. Invariants:
 * - The debounce runs on a private IO scope, NOT
 *   [androidx.lifecycle.viewModelScope]: a pending save must survive the
 *   screen being popped while its window is still open (R5). No UI references.
 * - ONE save pipeline: EVERY mutation — title, body, checkbox toggles,
 *   colour picks, AND label toggles — folds into the same pending state the
 *   one debounced job consumes; no side-channel write may interleave with an
 *   in-flight persistNow.
 * - [hasPendingSave] retires only AFTER [VaultStore.write] has RETURNED; a
 *   failed write keeps dirt set so the ON_STOP / onCleared flush retries it —
 *   the editor never claims "saved" ahead of durable bytes.
 * - ON_RESUME resync compares BYTES against the load-time snapshot — clocks
 *   decide nothing. Clean buffer + moved disk adopts the pulled bytes; dirty
 *   buffer + moved disk merges through [MergeEngine] or forks. A stale buffer
 *   is never written over moved disk bytes as a fresh edit.
 *
 * All vault I/O is [Dispatchers.IO]; each completed save enqueues an expedited
 * sync (§4.4); failed writes surface via [UiState.saveError] until cleared.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val ready: Boolean = false,
        val missing: Boolean = false,
        /**
         * The buffer's CURRENT authoritative title/body, kept fresh on every
         * edit so a recreation (rotation, theme change — the composition is
         * reborn while this view model survives) re-seeds the fields from
         * what the user actually sees, never from first-load text.
         */
        val initialTitle: String = "",
        val initialBody: String = "",
        /**
         * Monotonically bumped each time the view model produces new
         * authoritative screen text — the initial load and every post-resync
         * adopt/merge. The screen keys its one-shot field seeding on THIS
         * value (never on `Unit`), so ordinary recompositions leave typed
         * bytes alone while a recreation or an adopt re-seeds deliberately.
         */
        val generation: Long = 0L,
        val type: NoteType = NoteType.NOTE,
        /** Canonical Keep name for the current tone, or null when unpicked. */
        val colorName: String? = null,
        /** This note's archived flag (M6); mirrored optimistically on toggle. */
        val archived: Boolean = false,
        /** This note's labels (case-preserving, §8); mirrored optimistically on toggle. */
        val labels: List<String> = emptyList(),
        /** All labels in use across the vault, for the sheet's checkbox list. */
        val knownLabels: List<String> = emptyList(),
        /** One-line "not saved · reason" words while the last write failed. */
        val saveError: String? = null,
        /**
         * One-shot insertion event (WS10): the attachment link snippet plus
         * the offset it landed at in the authoritative body. The screen splices
         * its TextFieldValue to match and calls [consumeInsertion].
         */
        val insertion: Insertion? = null,
        /**
         * One-line plain words after a refused CAMERA prompt (P2.12, §13):
         * shown until the next capture attempt clears them. The editor stays
         * fully usable — typing and gallery insert are untouched.
         */
        val cameraWords: String? = null,
    )

    /** Where an inserted attachment link landed, for the screen's cursor repair. */
    data class Insertion(val offset: Int, val snippet: String)

    private val context get() = getApplication<Application>()

    private val store by lazy { VaultStore(context) }

    /**
     * The §10 attachment pipeline over the same mirror root the vault uses
     * (`attachments/` lives inside it). No client provider here: the editor
     * only INSERTS (hash/store/record); uploads are the sync engine's job.
     */
    private val attachments by lazy {
        AttachmentStore(
            vaultRoot = File(context.filesDir, VaultStore.MIRROR_DIR),
            dao = XxDatabase.getInstance(context).attachmentDao(),
        )
    }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var noteId: String

    /** The file's last-known whole bytes; every rewrite chains from here. */
    private var wholeFileText: String = ""

    /**
     * Editor-resync three-way base (§15): the whole-file bytes this editor
     * last knew the mirror to hold — captured at load and advanced by every
     * successful save, adopt, and merge. Disk-vs-this BYTE comparison decides
     * "the disk moved"; clocks are never consulted.
     */
    private var resyncBaseText: String = ""

    private var title: String = ""
    private var body: String = ""

    /**
     * Guards the buffer fields ([title], [body], [wholeFileText],
     * [resyncBaseText], labels, and the pending intents) against tears
     * between keystroke writes (main), the save pipeline (IO), and the §15
     * resync (IO). Hold times are microseconds; disk I/O never happens under
     * this lock.
     */
    private val bufferLock = Any()

    /** Serializes resync passes so a resume storm cannot double-merge. */
    private val resyncInFlight = AtomicBoolean(false)

    /**
     * Colour intent waiting to fold into the next save (H1). Consumed — and
     * cleared — by [persistNow] only, never written on its own coroutine.
     */
    private var pendingColor: String? = null

    /**
     * Archive intent (M6): same one-pipeline law as [pendingColor] — recorded
     * here, folded into the next debounced [persistNow], dropped on success.
     */
    private var pendingArchived: Boolean? = null

    /** This note's labels; mutated eagerly so rapid toggles compute correctly. */
    private var labels: List<String> = emptyList()

    /**
     * Label intents waiting to fold into the next save (H1, same pipeline as
     * [pendingColor]). Append-only copy-on-write: any later value keeps the
     * recorded list as a prefix, so [persistNow] drops exactly what it applied.
     */
    private var pendingLabelIntents: List<LabelIntent> = emptyList()

    private var saveJob: Job? = null

    /**
     * Hardening #2: true from the first unsaved mutation until a persist
     * lands durably — cleared only AFTER [VaultStore.write] returns, never
     * before it starts. The ON_STOP / onCleared flush persists only real
     * dirt (merely backgrounding a clean editor never rewrites the file),
     * and a FAILED write keeps the flag raised so the next flush or edit
     * retries. No instant exists where the editor claims saved while the
     * bytes are not yet durable.
     */
    @Volatile private var hasPendingSave = false

    /** Test/lifecycle visibility over the Hardening #2 dirty flag. */
    internal fun hasUnsavedEdits(): Boolean = hasPendingSave

    /**
     * Test seam behind every durable write the editor produces (same spirit
     * as [insertImageBytes]): production routes to [VaultStore.write]; tests
     * substitute failing/counting wrappers. The debounced save, the ON_STOP
     * flush, and the resync merge/fork all cross this one chokepoint.
     */
    internal var writeThrough: (String, String) -> Unit = { id, text -> store.write(id, text) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Loads by frontmatter `id`, never path (D3). A missing id closes the
     * screen immediately ([UiState.missing]) — defensive against stale nav
     * args after an external trash.
     */
    fun load(id: String) {
        noteId = id
        ioScope.launch {
            val text = try {
                store.read(id)?.wholeFileText
            } catch (_: Exception) {
                null
            }
            if (text == null) {
                _state.update { it.copy(missing = true) }
                return@launch
            }
            val doc = Frontmatter.parse(text)
            synchronized(bufferLock) {
                wholeFileText = text
                resyncBaseText = text
                title = doc.title.orEmpty()
                body = doc.bodyText
                labels = doc.labels
                pendingColor = null
                pendingArchived = null
                pendingLabelIntents = emptyList()
            }
            val known = runCatching {
                store.listLive()
                    .flatMap { Frontmatter.parse(it.wholeFileText).labels }
                    .distinctBy { it.lowercase() }
                    .sortedBy { it.lowercase() }
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    ready = true,
                    initialTitle = doc.title.orEmpty(),
                    initialBody = doc.bodyText,
                    // The screen seeds its fields off this bump; every later
                    // adopt/merge bumps again (§15 resync).
                    generation = it.generation + 1,
                    type = doc.type,
                    colorName = canonicalColorFor(toneForColor(doc.color)),
                    archived = doc.archived ?: false,
                    labels = doc.labels,
                    knownLabels = known,
                )
            }
        }
    }

    /**
     * Retitle: frontmatter only. The filename/slug is NOT regenerated — the
     * remote MOVE rename is a tracked follow-up (L3); §9 makes filenames
     * cosmetic, so correctness does not depend on it.
     */
    fun onTitleChange(value: String) {
        if (!isLoaded()) return
        synchronized(bufferLock) {
            title = value
            hasPendingSave = true
        }
        publishBuffer()
        scheduleSave()
    }

    fun onBodyChange(value: String) {
        if (!isLoaded()) return
        synchronized(bufferLock) {
            body = value
            hasPendingSave = true
        }
        publishBuffer()
        scheduleSave()
    }

    /**
     * Mirrors the buffer's current text into [UiState.initialTitle]/
     * [UiState.initialBody] WITHOUT bumping [UiState.generation]: the screen
     * re-seeds only when the generation key changes, but a REBORN composition
     * (rotation, theme change) re-runs its seeding effect once and must find
     * the user's latest bytes here — not the first-load text.
     */
    private fun publishBuffer() {
        synchronized(bufferLock) {
            val t = title
            val b = body
            _state.update { it.copy(initialTitle = t, initialBody = b) }
        }
    }

    /**
     * Checkbox tap: toggles the exact character via PURE [ChecklistToggle.at]
     * and schedules one debounced save. Same-length replacement means the
     * caller's TextFieldValue needs no selection repair.
     */
    fun toggleCheckboxAt(offset: Int): String? {
        if (!isLoaded()) return null
        val text = synchronized(bufferLock) {
            val result = ChecklistToggle.at(body, offset) ?: return null
            body = result.text
            hasPendingSave = true
            result.text
        }
        publishBuffer()
        scheduleSave()
        return text
    }

    /**
     * Colour picker commit (D12): writes ONE canonical Keep name for the tone
     * through `color:` — round-trip-safe for Obsidian/Keep readers — touches
     * nothing else. The intent folds into the SAME debounced pipeline as text
     * edits (H1): recorded, then consumed by the one [persistNow] that owns
     * every parse-and-write of the whole file, so it can never race an
     * in-flight save and lose a concurrent edit.
     */
    fun setColor(canonicalKeepName: String) {
        if (!isLoaded()) return
        synchronized(bufferLock) {
            pendingColor = canonicalKeepName
            hasPendingSave = true
        }
        scheduleSave()
    }

    /**
     * Archive toggle (M6): records the intent into the SAME debounced
     * pipeline as text, colour, and label edits — consumed by the one
     * [persistNow] owning every parse-and-write — and mirrors the flag
     * optimistically. The rewrite lands as a plain `archived:` value other
     * tools round-trip (§8), exactly like the batch fold does.
     */
    fun setArchived(value: Boolean) {
        if (!isLoaded()) return
        if (value == (pendingArchived ?: _state.value.archived)) return
        synchronized(bufferLock) {
            pendingArchived = value
            hasPendingSave = true
        }
        _state.update { it.copy(archived = value) }
        scheduleSave()
    }

    /**
     * Overflow Delete (M6): trash through [VaultStore.trash] — a D9 move to
     * `.xxnote/trash/`, never an unlink — then close via [onDeleted]. A
     * failed move speaks in words and keeps the editor open; nothing was
     * lost either way.
     */
    fun deleteNote(onDeleted: () -> Unit) {
        if (!isLoaded()) return
        ioScope.launch {
            val ok = try {
                store.trash(noteId)
                try {
                    SyncWorker.enqueueExpedited(context)
                } catch (_: Exception) {
                    // Sync spreads the trash; its absence never un-trashes.
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) onDeleted() else _state.update { it.copy(saveError = DELETE_FAILED_WORDS) }
        }
    }

    /**
     * Label checkbox toggle (WS8): records an add/remove [LabelIntent] that
     * folds into the SAME debounced pipeline as text and colour edits (H1) —
     * consumed by the one [persistNow] owning every parse-and-write, so a
     * label write can never interleave with an in-flight save. The in-memory
     * [labels] mirror updates eagerly (like title/body stay in memory on a
     * failed write); a rejected name surfaces in words via saveError.
     */
    fun toggleLabel(rawName: String) {
        setLabelPresent(rawName, present = !hasLabelCurrent(rawName))
    }

    /** Create-field add from the sheet: ensures presence; no-op when already on. */
    fun addLabel(rawName: String) {
        setLabelPresent(rawName, present = true)
    }

    private fun hasLabelCurrent(name: String): Boolean =
        labels.any { it.equals(name, ignoreCase = true) }

    private fun setLabelPresent(rawName: String, present: Boolean) {
        if (!isLoaded()) return
        val name = try {
            LabelOps.normalize(rawName)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(saveError = e.message ?: "label can't be used") }
            return
        }
        val changed: Boolean = synchronized(bufferLock) {
            val already = labels.any { it.equals(name, ignoreCase = true) }
            if (already == present) {
                false
            } else {
                labels = if (present) labels + name else labels.filterNot { it.equals(name, ignoreCase = true) }
                pendingLabelIntents = pendingLabelIntents + LabelIntent(add = present, name = name)
                hasPendingSave = true
                true
            }
        }
        if (!changed) return
        _state.update { it.copy(labels = synchronized(bufferLock) { labels.toList() }) }
        scheduleSave()
    }

    // ---- Attachment insert (WS10) ----------------------------------------------

    /**
     * Gallery insert (WS10): reads the photo-picker [uri]'s bytes off the
     * main thread, runs them through [AttachmentStore.insert] (HEIC
     * transcode → EXIF strip → SHA-256 address → dedup/store/record), and
     * drops `![](attachments/<16-hex>.<ext>)\n` at [cursorOffset] in the body
     * through the SAME pending-intent save pipeline as every other edit — no
     * direct concurrent writes (the setColor/labels discipline, H1). The
     * upload itself is nobody's job here: the debounced save enqueues the
     * sync, and the engine uploads the attachment before the body (§10).
     *
     * Camera capture rides the SAME seam (P2.12): the screen's TakePicture
     * result lands via [insertCapturedPhoto], which feeds already-read bytes
     * into [insertImageBytes] — identical addressing, EXIF strip, transcode,
     * and upload-before-body ordering downstream. §13 times the CAMERA
     * permission prompt to that first capture, never at launch.
     *
     * Failures speak in plain words via [UiState.saveError]; nothing is
     * inserted and nothing is saved that was not.
     */
    fun insertImage(uri: Uri, cursorOffset: Int) {
        if (!isLoaded()) return
        ioScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("picker returned an unreadable image")
                    val mime = context.contentResolver.getType(uri)
                    val ext = mime?.substringAfter('/')
                        ?.takeIf { it.isNotBlank() && it != "*" }
                        ?: DEFAULT_INSERT_EXT
                    attachments.insert(bytes, ext, mime)
                }
                insertLinkAtCursor(result.relativePath, cursorOffset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saveError = INSERT_FAILED_WORDS) }
            }
        }
    }

    /**
     * The pure seam behind [insertImage] — identical fold-into-save-pipeline
     * logic from already-read bytes, so tests run without a photo picker.
     */
    internal fun insertImageBytes(
        bytes: ByteArray,
        ext: String,
        mimeType: String?,
        cursorOffset: Int,
    ) {
        if (!isLoaded()) return
        ioScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    attachments.insert(bytes, ext, mimeType)
                }
                insertLinkAtCursor(result.relativePath, cursorOffset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saveError = INSERT_FAILED_WORDS) }
            }
        }
    }

    // ---- Camera capture (P2.12, §13) --------------------------------------------

    /**
     * Test seam behind reading a TakePicture target [Uri] (production:
     * [android.content.ContentResolver]; tests substitute a counting stub —
     * same spirit as [writeThrough]).
     */
    internal var readCaptureBytes: (Uri) -> ByteArray? = { uri ->
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    /**
     * Drops a TakePicture target after we have its bytes, or when the user
     * cancelled. FileProvider delete plus a cache-dir fallback so cancelled
     * captures do not sit in `cache/camera/` until the OS sweeps.
     */
    internal var discardCapture: (Uri) -> Unit = { uri ->
        runCatching { context.contentResolver.delete(uri, null, null) }
        uri.lastPathSegment?.let { name ->
            File(File(context.cacheDir, "camera"), name).delete()
        }
    }

    fun discardCaptureUri(uri: Uri) = discardCapture(uri)

    /**
     * Camera capture (P2.12): reads the TakePicture target [uri]'s bytes and
     * feeds them to [insertImageBytes], so capture rides the SAME pipeline as
     * the picker — SHA-256 address, EXIF strip, HEIC→JPEG transcode, and §10
     * upload-before-body ordering all happen downstream identically. The
     * capture file is deleted as soon as the bytes are in memory.
     */
    fun insertCapturedPhoto(uri: Uri, cursorOffset: Int) {
        if (!isLoaded()) return
        ioScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { readCaptureBytes(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                discardCapture(uri)
            }
            if (bytes == null) {
                _state.update { it.copy(saveError = INSERT_FAILED_WORDS) }
                return@launch
            }
            insertImageBytes(bytes, CAPTURE_EXT, CAPTURE_MIME, cursorOffset)
        }
    }

    /** §13 first-capture prompt refused: say it once; nothing else changes. */
    fun onCameraPermissionDenied() {
        _state.update { it.copy(cameraWords = CAMERA_DENIED_WORDS) }
    }

    /** The screen clears the words when a fresh capture attempt begins. */
    fun clearCameraWords() {
        _state.update { it.copy(cameraWords = null) }
    }

    /** Splices the Markdown link into the authoritative body and saves. */
    private fun insertLinkAtCursor(relativePath: String, cursorOffset: Int) {
        val snippet = IMAGE_LINK_PREFIX + relativePath + IMAGE_LINK_SUFFIX + "\n"
        val at = synchronized(bufferLock) {
            val cut = cursorOffset.coerceIn(0, body.length)
            body = body.substring(0, cut) + snippet + body.substring(cut)
            hasPendingSave = true
            cut
        }
        publishBuffer()
        _state.update { it.copy(insertion = Insertion(at, snippet)) }
        scheduleSave()
    }

    /** The screen acknowledges it spliced [UiState.insertion] into its field. */
    fun consumeInsertion() {
        _state.update { it.copy(insertion = null) }
    }

    // ---- Debounced save ------------------------------------------------------

    /**
     * Cancel-and-chain: at most one save job exists; a new intent cancels a
     * still-waiting window and opens a fresh one. A persist already past its
     * delay has no suspension points, so it always runs to completion before
     * the next one starts — all mutations are serialized through this job.
     *
     * The dirt flag rises HERE and retires only inside [persistNow] after
     * [VaultStore.write] has returned — never on a timer, never before the
     * bytes are durable (Hardening #2).
     */
    private fun scheduleSave() {
        hasPendingSave = true
        saveJob?.cancel()
        armDebounce()
    }

    /** One armed debounce window; the caller owns cancellation discipline. */
    private fun armDebounce() {
        saveJob = ioScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persistNow()
        }
    }

    /**
     * Hardening #2: the ON_STOP / onCleared last-write path. Cancels the
     * still-waiting debounce timer and persists the dirty buffer NOW through
     * the one [persistNow] pipeline — so process death inside the 800 ms
     * window can no longer discard typed text.
     *
     * Thread discipline: this call BLOCKS its caller (the lifecycle callback
     * on main) until the outcome is settled — the todo's explicit allowance
     * for a blocking ON_STOP write. The cancel-and-JOIN keeps H1's
     * serialization law intact even when a debounced persist is already
     * mid-write (past its delay, cancellation is cooperative): the flush
     * waits it out, then rewrites once from the newest fields only if dirt
     * SURVIVED that join. Because the flag retires only after
     * [VaultStore.write] returns, a failed write leaves [hasPendingSave]
     * true — the next ON_STOP / onCleared / keystroke retries it, and there
     * is no window where the flag says "saved" while the bytes are not yet
     * durable. A no-op unless something is actually unsaved.
     */
    fun flushPendingSave() {
        if (!isLoaded()) return
        synchronized(flushLock) {
            if (!hasPendingSave) return
            val waiting = saveJob
            saveJob = null
            runBlocking {
                waiting?.cancelAndJoin()
                if (hasPendingSave) persistNow()
            }
        }
    }

    private val flushLock = Any()

    /**
     * Hardening #2: second line of defence behind the screen's ON_STOP
     * observer — a back-navigation pop can tear the ViewModel down without
     * composition having flushed, so the flush must not rely on the UI at
     * all. The flush now runs to completion before [onCleared] returns: by
     * teardown either the bytes are durable or the failure is spoken in
     * words with dirt still raised for the next process to see nothing lose.
     */
    override fun onCleared() {
        super.onCleared()
        flushPendingSave()
    }

    /**
     * One save = one D18 rewrite, one vault write, one sync enqueue. The
     * dirt flag and pending intents retire ONLY after [VaultStore.write]
     * has RETURNED successfully — a failed write keeps them all raised so
     * the next flush or edit retries (H3's trashed refusal is the one
     * terminal case: replaying until restore would only re-refuse, so its
     * intents are dropped deliberately).
     */
    private suspend fun persistNow() {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val capture = captureBuffer()
        val next = assembleLocalText(now, capture)
        try {
            writeThrough(noteId, next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrashedNoteException) {
            // H3: the note was trashed underneath this editor (a batch delete
            // beat the debounce). The vault refused, so the trashed bytes are
            // untouched. Drop every pending intent — replaying them would only
            // re-refuse until the note is restored — retire the dirt (nothing
            // writable remains), and say so in words.
            synchronized(bufferLock) {
                pendingColor = null
                pendingLabelIntents = emptyList()
                pendingArchived = null
            }
            hasPendingSave = false
            _state.update { it.copy(saveError = NOT_SAVED_WORDS + TRASHED_REASON) }
            return
        } catch (e: Exception) {
            // Vault IO failure leaves state in memory AND the dirt flag up:
            // the next ON_STOP/onCleared flush or edit retries (Hardening #2).
            _state.update { it.copy(saveError = NOT_SAVED_WORDS + failureReason(e)) }
            return
        }
        synchronized(bufferLock) {
            wholeFileText = next
            resyncBaseText = next
            if (capture.color != null) pendingColor = null
            if (capture.labelIntents.isNotEmpty()) {
                pendingLabelIntents = pendingLabelIntents.drop(capture.labelIntents.size)
            }
            if (capture.archived != null) pendingArchived = null
        }
        // Only NOW — the write having returned — may the editor claim saved.
        hasPendingSave = false
        _state.update {
            it.copy(
                saveError = null,
                colorName = capture.color ?: it.colorName,
                labels = Frontmatter.parse(next).labels,
            )
        }
        try {
            // L8: same guard as every sibling site — a WorkManager refusal
            // must not crash after the save itself already succeeded.
            SyncWorker.enqueueExpedited(context)
        } catch (_: Exception) {
            // The bytes are durable in the mirror; sync retries on schedule.
        }
    }

    /** One locked snapshot of everything a persist or resync merge needs. */
    private class BufferShot(
        val baseChain: String,
        val resyncBase: String,
        val title: String,
        val body: String,
        val color: String?,
        val labelIntents: List<LabelIntent>,
        val archived: Boolean?,
        val dirty: Boolean,
    )

    private fun captureBuffer(): BufferShot {
        synchronized(bufferLock) {
            return BufferShot(
                baseChain = wholeFileText,
                resyncBase = resyncBaseText,
                title = title,
                body = body,
                color = pendingColor,
                labelIntents = pendingLabelIntents.toList(),
                archived = pendingArchived,
                dirty = hasPendingSave,
            )
        }
    }

    /**
     * The whole-file text the very next persist would write: current buffer
     * plus every pending intent folded (H1), from the snapshot's chain base.
     * Shared verbatim by the debounced save and the §15 resync merge, so
     * "ours" in any three-way merge is EXACTLY what the pipeline would have
     * written.
     */
    private fun assembleLocalText(nowIso: String, s: BufferShot): String {
        var next = buildSaveText(s.baseChain, s.title, s.body, nowIso)
        if (s.color != null) next = Frontmatter.parse(next).rewritten { this.color = s.color }
        if (s.labelIntents.isNotEmpty()) next = foldLabelIntents(next, s.labelIntents)
        if (s.archived != null) next = Frontmatter.parse(next).rewritten { archived = s.archived }
        return next
    }

    // ---- §15 editor resync (open editor vs background pull) -------------------

    /**
     * ON_RESUME re-read. The editor otherwise trusts its load-time bytes
     * forever, so a background sync pull rewriting the mirror while this
     * screen sits open would be discarded by the very next keystroke's save.
     * The comparison is BYTE-ONLY against [resyncBaseText] — clocks decide
     * nothing:
     *
     * - disk unchanged → no-op (clean or dirty alike; the debounce continues).
     * - clean buffer + disk moved → adopt the disk bytes wholesale and bump
     *   [UiState.generation] so the screen re-seeds from the pulled text.
     * - dirty buffer + disk moved → three-way merge through [MergeEngine]:
     *   base = [resyncBaseText], ours = exactly what the next save would
     *   write (pending intents folded), theirs = disk. Plain bodies merge
     *   line-wise; `type: checklist` bodies merge ITEM-wise via core
     *   ChecklistMerge — never Diff3. The merged bytes land immediately and
     *   the debounced job is joined and retired (its content is subsumed).
     * - unmergeable → FORK, never overwrite: the remote bytes become their
     *   own conflict-stamped note (fresh id + `conflictOf:`) beside the
     *   original, whose pipeline keeps the local side. Both sides stay
     *   readable; neither silently wins.
     *
     * A note trashed underneath us closes the editor exactly like an unknown
     * id ([UiState.missing]). Ruling on a keystroke racing the merge window
     * (sub-millisecond in practice): the merged bytes win and the racing
     * keystroke's own debounce re-fires over the merged base — a pulled edit
     * must never lose to a collision the user cannot perceive.
     */
    fun resyncFromDisk() {
        if (!isLoaded()) return
        if (!resyncInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                val diskText = try {
                    store.read(noteId)?.takeUnless { it.trashed }?.wholeFileText
                } catch (_: Exception) {
                    return@launch // transport/IO hiccup: the next resume retries
                }
                if (diskText == null) {
                    _state.update { it.copy(missing = true) }
                    return@launch
                }
                if (diskText == synchronized(bufferLock) { resyncBaseText }) {
                    return@launch // disk unmoved → no-op, clean or dirty alike
                }
                val shot = captureBuffer()
                when {
                    // adoptDisk re-checks dirt under the SAME lock hold that
                    // retires the debounce and swaps the buffers, so a
                    // keystroke racing the disk read downgrades this to a
                    // merge atomically — never a lost keystroke between
                    // "clean" and "adopted". The merge itself runs OFF the
                    // lock: the debounced job it joins re-acquires
                    // [bufferLock] on its own success path.
                    !shot.dirty -> {
                        if (!adoptDisk(diskText)) mergeWithDisk(diskText, captureBuffer())
                    }
                    else -> mergeWithDisk(diskText, shot)
                }
            } finally {
                resyncInFlight.set(false)
            }
        }
    }

    /**
     * Clean-buffer adoption: the pulled bytes become the editor's truth.
     * The dirt re-check, debounce retirement, and the authoritative swap all
     * happen under ONE [bufferLock] hold — a keystroke cannot slip between
     * "clean" and "adopted" and have its text silently overwritten. Returns
     * false when a keystroke raced in; the caller merges instead.
     */
    private fun adoptDisk(diskText: String): Boolean {
        var zombie: Job? = null
        synchronized(bufferLock) {
            if (hasPendingSave) return false
            zombie = saveJob
            saveJob = null
            hasPendingSave = false
            swapAuthoritativeFieldsLocked(diskText)
        }
        // Any job found here was armed while the flag read false, so it is
        // still inside its delay window and has captured nothing to join.
        zombie?.cancel()
        publishGeneration(diskText)
        return true
    }

    /** Caller holds [bufferLock]: swap every authoritative field onto [text]. */
    private fun swapAuthoritativeFieldsLocked(text: String) {
        val doc = Frontmatter.parse(text)
        wholeFileText = text
        resyncBaseText = text
        title = doc.title.orEmpty()
        body = doc.bodyText
        labels = doc.labels
    }

    /**
     * Swaps the editor onto [text] as the authoritative bytes (adopt or
     * merged result): chain base, resync base, buffer, mirrors, and a
     * [UiState.generation] bump so the screen re-seeds its fields.
     */
    private fun applyAuthoritativeText(text: String) {
        synchronized(bufferLock) { swapAuthoritativeFieldsLocked(text) }
        publishGeneration(text)
    }

    /** The generation bump that makes the screen re-seed its fields from [text]. */
    private fun publishGeneration(text: String) {
        val doc = Frontmatter.parse(text)
        _state.update {
            it.copy(
                initialTitle = doc.title.orEmpty(),
                initialBody = doc.bodyText,
                generation = it.generation + 1,
                type = doc.type,
                colorName = canonicalColorFor(toneForColor(doc.color)),
                archived = doc.archived ?: it.archived,
                labels = doc.labels,
                saveError = null,
            )
        }
    }

    /**
     * Dirty buffer + moved disk: three-way merge (see [resyncFromDisk]).
     * Runs off the lock for compute and I/O, then applies under it.
     */
    private fun mergeWithDisk(diskText: String, shot: BufferShot) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val ours = assembleLocalText(now, shot)
        when (val outcome = MergeEngine.merge(shot.resyncBase, ours, diskText)) {
            is MergeEngine.MergeOutcome.Merged -> landMerged(outcome.wholeFileText, shot, diskText)
            is MergeEngine.MergeOutcome.Fork -> forkRemoteSide(diskText)
        }
    }

    /**
     * The merged bytes subsume everything the debounced job was going to
     * write: join that job out, write once through the same chokepoint, then
     * swap state. If the merged write FAILS, a joined persist may already
     * have overwritten the pulled bytes on disk with the local buffer —
     * park those pulled bytes as their own note so they stay readable, then
     * re-arm the local pipeline with dirt raised.
     */
    private fun landMerged(merged: String, shot: BufferShot, diskText: String) {
        val waiting = synchronized(bufferLock) { val w = saveJob; saveJob = null; w }
        runBlocking { waiting?.cancelAndJoin() }
        try {
            writeThrough(noteId, merged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            parkPulledBytes(diskText)
            hasPendingSave = true
            armDebounce()
            _state.update { it.copy(saveError = NOT_SAVED_WORDS + failureReason(e)) }
            return
        }
        hasPendingSave = false
        synchronized(bufferLock) {
            wholeFileText = merged
            resyncBaseText = merged
            if (shot.color != null) pendingColor = null
            if (shot.labelIntents.isNotEmpty()) {
                pendingLabelIntents = pendingLabelIntents.drop(shot.labelIntents.size)
            }
            if (shot.archived != null) pendingArchived = null
        }
        applyAuthoritativeText(merged)
        try {
            SyncWorker.enqueueExpedited(context)
        } catch (_: Exception) {
            // Bytes durable in the mirror; sync retries on schedule.
        }
    }

    /**
     * Unmergeable: the remote side forks into its own note — fresh identity,
     * `conflictOf:`/`conflictAt:` stamped, exactly like an engine fork — so
     * both texts exist after this returns. The original's pipeline keeps the
     * local side untouched (its debounce persists as usual). If even the fork
     * write fails, the sides INVERT: our assembled text forks instead and the
     * remote bytes are adopted into the original slot — still both, still no
     * overwrite of readable content.
     */
    private fun forkRemoteSide(diskText: String) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val freshId = Ulid.generate()
        val forkText = Frontmatter.parse(diskText).rewritten {
            id = freshId
            conflictOf = noteId
            conflictAt = now
        }
        try {
            writeThrough(freshId, forkText)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall through to the inversion below.
        }
        val shot = captureBuffer()
        val invertedId = Ulid.generate()
        val inverted = Frontmatter.parse(assembleLocalText(now, shot)).rewritten {
            id = invertedId
            conflictOf = noteId
            conflictAt = now
        }
        try {
            writeThrough(invertedId, inverted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Both fork writes refused: storage itself is failing. Join any
            // mid-flight persist out FIRST — cancel alone cannot stop one
            // already past its delay, and its captured pre-merge buffer would
            // overwrite the pulled bytes on disk. With the join settled, give
            // the remote fork ONE more try in case the wedge was transient.
            // Still failing: keep whatever dirt the joined persist left and
            // speak — the next resume retries the resync.
            val waiting = synchronized(bufferLock) { val w = saveJob; saveJob = null; w }
            runBlocking { waiting?.cancelAndJoin() }
            try {
                writeThrough(freshId, forkText)
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: Exception) {
                _state.update { it.copy(saveError = NOT_SAVED_WORDS + failureReason(e)) }
            }
            return
        }
        // Our text now rests in its own fork; the original slot adopts the
        // remote bytes. Join any mid-flight persist BEFORE swapping — cancel
        // alone cannot stop one already past its delay, and its pre-merge
        // capture would clobber the adoption. That join may itself have
        // written local text over the pulled bytes on disk, so the adopt
        // RESTORES the remote bytes through the same chokepoint first (the
        // vault leads; memory never claims bytes the mirror does not hold).
        val waiting = synchronized(bufferLock) { val w = saveJob; saveJob = null; w }
        runBlocking { waiting?.cancelAndJoin() }
        try {
            writeThrough(noteId, diskText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The local side is durable in its fork; the pulled bytes could
            // not be restored to the mirror. Keep the joined persist's dirt
            // state and speak — the next resume retries the resync.
            _state.update { it.copy(saveError = NOT_SAVED_WORDS + failureReason(e)) }
            return
        }
        val raced: Boolean
        synchronized(bufferLock) {
            raced = hasPendingSave
            if (!raced) {
                // The inverted fork consumed every pending intent.
                pendingColor = null
                pendingLabelIntents = emptyList()
                pendingArchived = null
                hasPendingSave = false
            }
        }
        if (raced) {
            // A keystroke landed during the failed writes: its text sits in
            // buffers NEWER than the fork's snapshot, dirt raised, debounce
            // armed. Letting that debounce fire would overwrite the just-
            // restored remote bytes — so merge against them NOW (forking
            // again if unmergeable) instead of swapping onto them.
            mergeWithDisk(diskText, captureBuffer())
            return
        }
        applyAuthoritativeText(diskText)
    }

    /**
     * Best-effort park of pulled bytes as a conflict-stamped note. Used when
     * a merge write failed after a joined persist may have already replaced
     * the mirror with the local buffer. Failure here is silent: the pulled
     * bytes still live on the server, local dirt stays raised, resume retries.
     */
    private fun parkPulledBytes(diskText: String) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        val freshId = Ulid.generate()
        val forkText = Frontmatter.parse(diskText).rewritten {
            id = freshId
            conflictOf = noteId
            conflictAt = now
        }
        try {
            writeThrough(freshId, forkText)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Local dirt stays; the server still holds the pulled bytes.
        }
    }

    private fun isLoaded(): Boolean =
        this::noteId.isInitialized && _state.value.ready && !_state.value.missing

    /** Plain words for a failed write: message when readable, class name otherwise. */
    private fun failureReason(e: Exception): String = e.message ?: e.javaClass.simpleName

    companion object {
        const val SAVE_DEBOUNCE_MS = 800L

        /** The Markdown link wrapper an inserted attachment lands in (§10). */
        internal const val IMAGE_LINK_PREFIX = "![]("
        internal const val IMAGE_LINK_SUFFIX = ")"

        /** Extension fallback when the picker offers no usable mime type. */
        internal const val DEFAULT_INSERT_EXT = "jpg"

        /** WS10: a failed gallery insert speaks in one line; nothing changed. */
        internal const val INSERT_FAILED_WORDS =
            "couldn't add the image — storage problem · nothing was inserted — try again"

        /** P2.12: CAMERA denied at first capture (§13) — plain words, no nag. */
        internal const val CAMERA_DENIED_WORDS =
            "no camera permission — typing and gallery insert still work"

        /** TakePicture targets a JPEG (the camera app's own encode). */
        internal const val CAPTURE_EXT = "jpg"
        internal const val CAPTURE_MIME = "image/jpeg"

        /** §15 disk-full row, editor flavour (L10): one line until saved. */
        internal const val NOT_SAVED_WORDS = "not saved · "

        /** H3: the vault refused because the note rests in trash. */
        internal const val TRASHED_REASON = "that note is in trash — restore it to edit again"

        /** M6: overflow Delete failed before the move; the note is unchanged. */
        internal const val DELETE_FAILED_WORDS =
            "couldn't delete — storage problem · the note is unchanged"
    }
}
