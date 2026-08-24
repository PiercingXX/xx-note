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
import com.piercingxx.xxnote.sync.SyncWorker
import com.piercingxx.xxnote.ui.grid.canonicalColorFor
import com.piercingxx.xxnote.ui.grid.toneForColor
import com.piercingxx.xxnote.ui.labels.LabelIntent
import com.piercingxx.xxnote.ui.labels.LabelOps
import com.piercingxx.xxnote.ui.labels.foldLabelIntents
import java.io.File
import java.io.IOException
import java.time.Instant
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
import kotlinx.coroutines.withContext

/**
 * WS7 editor state holder (design §12 item 2). Owns the authoritative
 * title/body strings, the 800 ms debounced save (D18/O3: sort-to-bottom once
 * per save, editor-only), and the colour-tone write.
 *
 * **Save scheduling ruling.** The debounce timer runs on a private IO scope,
 * NOT [androidx.lifecycle.viewModelScope]: a pending save must survive the
 * screen being popped while its 800 ms window is still open — R5 outranks a
 * tidy lifecycle. The scope holds no Android UI references; it dies with the
 * process at worst, and the vault file write is atomic either way (§15).
 *
 * **One save pipeline (H1 ruling).** EVERY mutation — title, body, checkbox
 * toggles, colour picks, AND label toggles (WS8) — folds into the same
 * pending state that the one
 * debounced job consumes. `setColor` no longer parses or writes the file on
 * its own coroutine: it records the intent and joins the same cancel-and-
 * chain debounce, so a colour write can never interleave with an in-flight
 * persistNow and lose a concurrent text edit (the old lost-update race).
 *
 * All vault I/O is [Dispatchers.IO]; typing never blocks. Every completed
 * save enqueues an expedited sync (§4.4). A failed vault write surfaces in
 * words via [UiState.saveError] (§15 disk-full row: the editor shows the
 * failure; nothing is marked saved that was not) until the next successful
 * save clears it.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val ready: Boolean = false,
        val missing: Boolean = false,
        /** Initial values only; the screen mirrors them into TextFieldValue once. */
        val initialTitle: String = "",
        val initialBody: String = "",
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

    private var title: String = ""
    private var body: String = ""

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
     * Hardening #2: true from the first unsaved mutation until its debounced
     * [persistNow] begins. The ON_STOP / onCleared flush persists only real
     * dirt, so merely backgrounding a clean editor never rewrites the file.
     */
    @Volatile private var hasPendingSave = false

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
            wholeFileText = text
            title = doc.title.orEmpty()
            body = doc.bodyText
            labels = doc.labels
            val known = runCatching {
                store.listLive()
                    .flatMap { Frontmatter.parse(it.wholeFileText).labels }
                    .distinctBy { it.lowercase() }
                    .sortedBy { it.lowercase() }
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    ready = true,
                    initialTitle = title,
                    initialBody = body,
                    type = doc.type,
                    colorName = canonicalColorFor(toneForColor(doc.color)),
                    archived = doc.archived ?: false,
                    labels = labels,
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
        title = value
        scheduleSave()
    }

    fun onBodyChange(value: String) {
        if (!isLoaded()) return
        body = value
        scheduleSave()
    }

    /**
     * Checkbox tap: toggles the exact character via PURE [ChecklistToggle.at]
     * and schedules one debounced save. Same-length replacement means the
     * caller's TextFieldValue needs no selection repair.
     */
    fun toggleCheckboxAt(offset: Int): String? {
        if (!isLoaded()) return null
        val result = ChecklistToggle.at(body, offset) ?: return null
        body = result.text
        scheduleSave()
        return result.text
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
        pendingColor = canonicalKeepName
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
        pendingArchived = value
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
        if (hasLabelCurrent(name) == present) return
        labels = if (present) labels + name else labels.filterNot { it.equals(name, ignoreCase = true) }
        pendingLabelIntents = pendingLabelIntents + LabelIntent(add = present, name = name)
        _state.update { it.copy(labels = labels.toList()) }
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
     * CAMERA capture is a deliberate v1 follow-up: TakePicture would need a
     * CAMERA-permission prompt flow whose timing §13 reserves for first
     * capture; the photo picker needs no permission at all.
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

    /** Splices the Markdown link into the authoritative body and saves. */
    private fun insertLinkAtCursor(relativePath: String, cursorOffset: Int) {
        val snippet = IMAGE_LINK_PREFIX + relativePath + IMAGE_LINK_SUFFIX + "\n"
        val at = cursorOffset.coerceIn(0, body.length)
        body = body.substring(0, at) + snippet + body.substring(at)
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
     */
    private fun scheduleSave() {
        hasPendingSave = true
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            hasPendingSave = false
            persistNow()
        }
    }

    /**
     * Hardening #2: the ON_STOP / onCleared last-write path. Cancels the
     * still-waiting debounce timer and persists the dirty buffer NOW through
     * the one [persistNow] pipeline — so process death inside the 800 ms
     * window can no longer discard typed text.
     *
     * Thread discipline: the call lands on the MAIN thread (lifecycle
     * callback), but all work hands off to the private IO scope, which
     * outlives this ViewModel by design — a job enqueued here survives
     * [onCleared] teardown. The cancel-and-JOIN keeps H1's serialization law
     * intact even when a debounced persist is already mid-write (past its
     * delay, cancellation is cooperative): the flush waits it out, then
     * rewrites once from the newest fields. A no-op unless something is
     * actually unsaved ([hasPendingSave]).
     */
    fun flushPendingSave() {
        if (!isLoaded()) return
        if (!hasPendingSave) return
        hasPendingSave = false
        val waiting = saveJob
        saveJob = null
        ioScope.launch {
            waiting?.cancelAndJoin()
            persistNow()
        }
    }

    /**
     * Hardening #2: second line of defence behind the screen's ON_STOP
     * observer — a back-navigation pop can tear the ViewModel down without
     * composition having flushed, so the flush must not rely on the UI at
     * all. [flushPendingSave] enqueues onto the outliving IO scope before
     * this returns; the write itself proceeds after teardown. Main-thread
     * safe by construction (no blocking hand-off needed).
     */
    override fun onCleared() {
        super.onCleared()
        flushPendingSave()
    }

    /** One save = one D18 rewrite, one vault write, one sync enqueue. */
    private suspend fun persistNow() {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
        var next = buildSaveText(wholeFileText, title, body, now)
        val color = pendingColor
        if (color != null) {
            next = Frontmatter.parse(next).rewritten { this.color = color }
        }
        // Label intents replay in recording order over the assembled save text
        // (both ops are idempotent, so the fold converges). Snapshot is a
        // prefix of any later pending list; only it is dropped on success.
        val labelIntents = pendingLabelIntents
        if (labelIntents.isNotEmpty()) {
            next = foldLabelIntents(next, labelIntents)
        }
        // M6: the archive intent rides the same single write.
        val archivedIntent = pendingArchived
        if (archivedIntent != null) {
            next = Frontmatter.parse(next).rewritten { archived = archivedIntent }
        }
        try {
            store.write(noteId, next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrashedNoteException) {
            // H3: the note was trashed underneath this editor (a batch delete
            // beat the debounce). The vault refused, so the trashed bytes are
            // untouched. Drop every pending intent — replaying them would only
            // re-refuse until the note is restored — and say so in words.
            pendingColor = null
            pendingLabelIntents = emptyList()
            pendingArchived = null
            _state.update { it.copy(saveError = NOT_SAVED_WORDS + TRASHED_REASON) }
            return
        } catch (e: Exception) {
            // Vault IO failure leaves state in memory; next edit retries.
            _state.update { it.copy(saveError = NOT_SAVED_WORDS + failureReason(e)) }
            return
        }
        wholeFileText = next
        if (color != null) pendingColor = null
        if (labelIntents.isNotEmpty()) pendingLabelIntents = pendingLabelIntents.drop(labelIntents.size)
        if (archivedIntent != null) pendingArchived = null
        _state.update {
            it.copy(
                saveError = null,
                colorName = color ?: it.colorName,
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

        /** §15 disk-full row, editor flavour (L10): one line until saved. */
        internal const val NOT_SAVED_WORDS = "not saved · "

        /** H3: the vault refused because the note rests in trash. */
        internal const val TRASHED_REASON = "that note is in trash — restore it to edit again"

        /** M6: overflow Delete failed before the move; the note is unchanged. */
        internal const val DELETE_FAILED_WORDS =
            "couldn't delete — storage problem · the note is unchanged"
    }
}
