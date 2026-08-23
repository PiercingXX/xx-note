package com.piercingxx.xxnote.ui.labels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.sync.SyncWorker
import com.piercingxx.xxnote.ui.grid.NoteCard
import com.piercingxx.xxnote.ui.grid.buildNoteCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WS8 filtered-grid state holder (design §12 item 3): the live notes carrying
 * one label, as [NoteCard]s built by the PURE [buildNoteCard] from whole-file
 * text (D1: file wins). Matching is case-insensitive per §8.
 */
class LabelGridViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val cards: List<NoteCard> = emptyList(),
        /** Plain words for the last failed read/unassign. */
        val notice: String? = null,
    )

    private val context get() = getApplication<Application>()

    private val store by lazy { VaultStore(context) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The label this screen filters by; set via [open]. */
    private var query: String = ""

    /**
     * Bind to the navigated label and load. Called on entry and whenever the
     * nav arg changes; also re-applied on every resume via the screen's
     * lifecycle observer calling [refresh].
     */
    fun open(label: String) {
        query = label
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    private fun load(): UiState {
        val previous = _state.value
        if (query.isBlank()) {
            // M5: a refresh never wipes a standing notice — words persist
            // until their next attempt clears or replaces them.
            return UiState(loading = false, cards = previous.cards, notice = previous.notice)
        }
        return try {
            // Ruling beyond spec: same ordering as the home grid —
            // most-recently-updated first, id as deterministic tie-break.
            // H1: archived notes stay out of label grids too.
            val cards = store.listLive()
                .map { buildNoteCard(it.id, it.wholeFileText) }
                .filterNot { it.archived }
                .filter { LabelOps.hasLabel(it.labels, query) }
                .sortedWith(
                    compareByDescending<NoteCard> { it.updatedAtMillis }.thenByDescending { it.id },
                )
            UiState(loading = false, cards = cards, notice = previous.notice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UiState(
                loading = false,
                cards = previous.cards,
                notice = READ_FAILED_WORDS + (e.message ?: e.javaClass.simpleName),
            )
        }
    }

    /**
     * Unassign this grid's label from one card: re-reads the note fresh
     * (file wins), applies the PURE [LabelOps.removeLabel], writes through
     * the vault. A failure speaks in words and keeps the card tagged.
     */
    fun unassign(noteId: String, label: String) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    val text = store.read(noteId)?.wholeFileText ?: return@withContext null
                    store.write(noteId, LabelOps.removeLabel(text, label))
                    try {
                        SyncWorker.enqueueExpedited(context)
                    } catch (_: Exception) {
                        // Local truth is already written; sync follows later.
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            if (failure != null) {
                _state.value = _state.value.copy(notice = UNASSIGN_FAILED_WORDS + failure)
            }
            refresh()
        }
    }

    companion object {
        internal const val READ_FAILED_WORDS = "couldn't read the vault · "
        internal const val UNASSIGN_FAILED_WORDS = "couldn't update that note · "
    }
}
