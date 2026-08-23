package com.piercingxx.xxnote.ui.archive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.sync.SyncWorker
import com.piercingxx.xxnote.ui.grid.NoteCard
import com.piercingxx.xxnote.ui.grid.buildNoteCard
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WS8 archive state holder (design §12 item 3: Archive is a first-class verb,
 * distinct from delete). The live mirror is re-listed on every resume (D1:
 * file wins), filtered to `archived: true` by the PURE [ArchiveFilter], and
 * each whole-file text is mapped to a [NoteCard] by the PURE grid mapper.
 * All disk/Room access rides [Dispatchers.IO]; [VaultStore]'s synchronous port
 * contract is never invoked from the main thread.
 */
class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val rows: List<NoteCard> = emptyList(),
        /** True while an unarchive is in flight; one tap, one rewrite. */
        val busy: Boolean = false,
        /** Plain words for the last failed unarchive; kept until the next attempt. */
        val notice: String? = null,
    )

    private val context get() = getApplication<Application>()

    private val store by lazy { VaultStore(context) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-scans the mirror and rebuilds every archived row; cheap, run on (re)entry. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    private fun load(): UiState {
        val previous = _state.value
        return try {
            val archived = ArchiveFilter.filterArchived(store.listLive()) { it.wholeFileText }
            val cards = archived.map { buildNoteCard(it.id, it.wholeFileText) }
            // Ruling beyond spec, same muscle memory as the home grid (WS7):
            // most-recently-updated first, id descending as deterministic tie-break.
            val sorted = cards.sortedWith(
                compareByDescending<NoteCard> { it.updatedAtMillis }.thenByDescending { it.id },
            )
            // M5: preserve busy + notice like the GridViewModel precedent —
            // a failed unarchive's words must survive the refresh that follows.
            UiState(loading = false, rows = sorted, busy = previous.busy, notice = previous.notice)
        } catch (_: Exception) {
            // A mirror that cannot be listed is spoken, never crashed (§15).
            UiState(
                loading = false,
                rows = previous.rows,
                busy = previous.busy,
                notice = LIST_FAILED_WORDS,
            )
        }
    }

    /**
     * Unarchive: flip frontmatter `archived` to false through
     * [FrontmatterDocument.rewritten] via [VaultStore.write] — a plain vault
     * write, byte-exact for everything XX-Note does not own — then enqueue an
     * expedited sync so peers converge fast. The sync enqueue failing never
     * undoes the write; it retries on schedule.
     *
     * Ruling beyond spec: the rewrite also stamps `updated:` with the action's
     * instant, mirroring what [VaultStore.trash] does on the way in. Archiving
     * is a user metadata change; its moment belongs in the file.
     */
    fun unarchive(id: String) {
        if (_state.value.busy) return // swallow double-taps before they double-write
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val words = withContext(Dispatchers.IO) {
                try {
                    val note = store.read(id)
                    when {
                        note == null -> ALREADY_GONE_WORDS
                        else -> {
                            val doc = Frontmatter.parse(note.wholeFileText)
                            store.write(
                                id,
                                doc.rewritten {
                                    archived = false
                                    updated = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                                },
                            )
                            try {
                                SyncWorker.enqueueExpedited(context)
                            } catch (_: Exception) {
                                // Sync spreads the unarchive; its absence never un-unarchives.
                            }
                            null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    UNARCHIVE_FAILED_WORDS
                }
            }
            _state.update { it.copy(busy = false, notice = words) }
            refresh()
        }
    }

    companion object {
        internal const val UNARCHIVE_FAILED_WORDS =
            "couldn't unarchive — storage problem · the note stays as it was"
        internal const val ALREADY_GONE_WORDS =
            "that note is gone already — nothing to unarchive"
        internal const val LIST_FAILED_WORDS =
            "couldn't read the vault · nothing was changed"
    }
}
