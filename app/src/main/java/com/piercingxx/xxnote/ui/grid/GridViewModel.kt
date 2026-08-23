package com.piercingxx.xxnote.ui.grid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piercingxx.xxnote.core.NoteType
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.sync.LocalNote
import com.piercingxx.xxnote.sync.SyncWorker
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WS7/WS8 home state holder (design §12 item 1). The vault mirror is
 * re-listed on every resume (D1: file wins) and each note's whole-file text
 * is mapped to a [NoteCard] by the PURE [buildNoteCard]. All disk/Room access
 * rides [Dispatchers.IO]; [VaultStore]'s synchronous port contract is never
 * invoked from the main thread.
 *
 * WS8 adds two state machines, both pure and reducer-tested:
 * - search — [SearchFieldState] + a debounced (~[SEARCH_DEBOUNCE_MS] ms)
 *   FTS query. **R4: search never touches the network.** The query goes to
 *   the local Room cache (`note_fts` over title+body) through
 *   [SearchQuery.sanitize], results are re-projected into cards from the
 *   row's whole-file text, and an empty query restores the normal grid.
 *   Ruling: [VaultStore] exposes no FTS port this wave and its file is owned
 *   by another workstream, so the view model opens its own Room handle via
 *   [XxDatabase.builder] (same db file; Room supports concurrent handles)
 *   instead of editing data/.
 * - multi-select — [SelectionState]; batch actions fold pure
 *   [MultiSelectOps] intents over each selected note's whole-file text,
 *   sequentially on IO, then write once per note through VaultStore.write.
 */
class GridViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val pinned: List<NoteCard> = emptyList(),
        val others: List<NoteCard> = emptyList(),
        /** True while a capture write is in flight; one tap, exactly one note. */
        val capturing: Boolean = false,
        /** Plain words for the last failed capture; kept until the next attempt. */
        val notice: String? = null,
        // ---- WS8 search ----
        val field: SearchFieldState = SearchFieldState(),
        /** Result cards for the settled query; empty while none apply. */
        val results: List<NoteCard> = emptyList(),
        /** True from keystroke until a result set for THAT query landed. */
        val searching: Boolean = false,
        // ---- WS8 multi-select ----
        val selection: SelectionState = SelectionState(),
        /** True while a batch is being folded+written; the action bar disables. */
        val applyingBatch: Boolean = false,
        /** One plain-words notice for a batch with failures; see [batchNotice]. */
        val batchNotice: String? = null,
    )

    private val context get() = getApplication<Application>()

    private val store by lazy { VaultStore(context) }

    /**
     * Read handle for FTS (see class KDoc ruling). Lazy so a session that
     * never searches never opens a second connection.
     */
    private val searchDb by lazy { XxDatabase.builder(context).build() }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Monotonic guard: only the newest query may land its results. */
    private var searchEpoch = 0L

    init {
        refresh()
    }

    /** Re-scans the mirror and rebuilds every card; cheap, run on (re)entry. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    private fun load(): UiState {
        val previous = _state.value
        val cards = store.listLive().map { buildNoteCard(it.id, it.wholeFileText) }
        val (pinnedCards, otherCards) = projectHome(cards)
        return UiState(
            loading = false,
            pinned = pinnedCards,
            others = otherCards,
            capturing = previous.capturing,
            notice = previous.notice,
            field = previous.field,
            results = previous.results,
            searching = previous.searching,
            selection = previous.selection,
            applyingBatch = previous.applyingBatch,
            batchNotice = previous.batchNotice,
        )
    }

    // ---- Capture (WS7) -------------------------------------------------------

    /**
     * Capture (R1): creates the note NOW — fresh ULID, frontmatter template —
     * then hands the id back for navigation. No spinner state exists anywhere
     * in this path; the editor is the next thing the user sees. Runs on IO;
     * [onCreated] is invoked on the main dispatcher.
     *
     * A failed vault write (§15 disk-full row) never crashes and never
     * navigates: the words land in [UiState.notice], the draft stays in the
     * capture bar untouched, and the very next tap retries.
     */
    fun capture(draftTitle: String, type: NoteType, onCreated: (String) -> Unit) {
        if (_state.value.capturing) return // swallow double-taps before they double-mint
        _state.update { it.copy(capturing = true, notice = null) }
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                try {
                    val noteId = Ulid.generate()
                    val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                    store.write(noteId, captureTemplate(noteId, draftTitle.trim(), type, now))
                    try {
                        SyncWorker.enqueueExpedited(context)
                    } catch (_: Exception) {
                        // Sync is how the note leaves; its absence never uncreates it.
                    }
                    noteId
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // keep the draft in memory; the UI offers retry in words
                }
            }
            if (id == null) {
                _state.update { it.copy(capturing = false, notice = CAPTURE_SAVE_FAILED_WORDS) }
            } else {
                _state.update { it.copy(capturing = false) }
                onCreated(id)
            }
        }
    }

    // ---- Search (WS8, R4: local cache only) ------------------------------------

    fun expandSearch() = _state.update {
        it.copy(field = reduceSearchField(it.field, SearchFieldEvent.Expand))
    }

    /** Leaving search always restores the normal grid (query reset, R4). */
    fun collapseSearch() {
        searchJob?.cancel()
        searchJob = null
        searchEpoch++
        _state.update {
            it.copy(
                field = reduceSearchField(it.field, SearchFieldEvent.Collapse),
                results = emptyList(),
                searching = false,
            )
        }
    }

    /**
     * Keystroke → sanitize → debounce → `NoteDao.search` on IO. Results that
     * arrive after a newer keystroke are dropped by epoch check; a sanitized-
     * empty query clears instantly without touching the index.
     */
    fun onQueryChange(raw: String) {
        _state.update { it.copy(field = reduceSearchField(it.field, SearchFieldEvent.SetQuery(raw))) }
        searchJob?.cancel()
        val sanitized = SearchQuery.sanitize(raw)
        if (sanitized.isEmpty()) {
            searchEpoch++
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchEpoch++
        val epoch = searchEpoch
        _state.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            var refused = false
            val rows = withContext(Dispatchers.IO) {
                try {
                    searchDb.noteDao().search(sanitized)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    refused = true // L7: a refusal must never masquerade as no-results
                    null
                }
            }
            if (epoch != searchEpoch || !_state.value.field.isActive) return@launch
            if (refused || rows == null) {
                // L7: FTS failure speaks AND falls back to the unfiltered live
                // list — the user sees their notes, not a lying empty state.
                val fallback = withContext(Dispatchers.IO) {
                    runCatching { store.listLive() }.getOrDefault(emptyList())
                }
                _state.update {
                    it.copy(
                        searching = false,
                        notice = SEARCH_FAILED_WORDS,
                        results = searchFallbackCards(fallback),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    searching = false,
                    notice = null,
                    results = rows.map { row -> buildNoteCard(row.id, row.body) }
                        .filterNot { card -> card.archived }, // H1: archived stays out of search
                )
            }
        }
    }

    // ---- Multi-select (WS8) ------------------------------------------------------

    fun onCardLongPress(id: String) = _state.update {
        it.copy(selection = reduceSelection(it.selection, SelectionAction.LongPress(id)))
    }

    fun toggleSelect(id: String) = _state.update {
        it.copy(selection = reduceSelection(it.selection, SelectionAction.Tap(id)))
    }

    fun exitSelection() = _state.update {
        it.copy(selection = reduceSelection(it.selection, SelectionAction.Exit))
    }

    /**
     * Pin/unpin target for the action bar's single toggle button: PIN when ANY
     * selected card is unpinned (one tap pins them all), UNPIN only when every
     * card already carries the pin.
     */
    fun pinTargetForSelection(): Boolean {
        val s = _state.value
        val selected = s.pinned + s.others + s.results
        return selected.filter { it.id in s.selection.ids }.any { !it.pinned }
    }

    /**
     * Applies one [BatchAction] across the whole selection. Sequential on IO
     * (§12 item 1 ordering), one vault write per changed note, failures of
     * every kind — exceptions AND malformed-frontmatter skips — collected and
     * surfaced as ONE plain-words notice ([batchNotice]); nothing crashes.
     * Afterwards: selection exits, the mirror re-scans, search resets to the
     * normal grid, and exactly ONE expedited sync is enqueued for the batch.
     */
    fun applyBatch(action: BatchAction) {
        val current = _state.value
        val ids = current.selection.ids.toList()
        if (ids.isEmpty() || current.applyingBatch) return
        _state.update { it.copy(applyingBatch = true, batchNotice = null) }
        viewModelScope.launch {
            var failed = 0
            withContext(Dispatchers.IO) {
                for (id in ids) {
                    val ok = try {
                        applyOne(id, action)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (!ok) failed++
                }
            }
            try {
                SyncWorker.enqueueExpedited(context)
            } catch (_: Exception) {
                // The writes are durable in the mirror; sync retries later anyway.
            }
            _state.update {
                it.copy(
                    applyingBatch = false,
                    selection = reduceSelection(it.selection, SelectionAction.Exit),
                    field = reduceSearchField(it.field, SearchFieldEvent.Collapse),
                    results = emptyList(),
                    searching = false,
                    batchNotice = batchNotice(failed, ids.size),
                )
            }
            refresh()
        }
    }

    /** One note's share of a batch. False = count me as failed. */
    private fun applyOne(id: String, action: BatchAction): Boolean {
        if (action is BatchAction.Trash) {
            store.trash(id) // throws for unknown ids → counted, never fatal
            return true
        }
        val note = store.read(id) ?: return false
        val rewritten = when (action) {
            is BatchAction.Pin -> MultiSelectOps.applyPin(note.wholeFileText, action.pinned)
            is BatchAction.Archive -> MultiSelectOps.applyArchive(note.wholeFileText, action.archived)
            is BatchAction.Color -> MultiSelectOps.applyColor(note.wholeFileText, action.keepName)
            BatchAction.Trash -> return true // handled above; keeps `when` total
        } ?: return false // malformed frontmatter → skip-and-report
        if (rewritten != note.wholeFileText) store.write(id, rewritten)
        return true
    }

    companion object {
        /** §15 disk-full row, capture flavour: nothing was saved, the draft is kept. */
        internal const val CAPTURE_SAVE_FAILED_WORDS =
            "couldn't save — storage problem · your text is kept — try again"

        /** L7: an FTS refusal says what happened and what the grid did instead. */
        internal const val SEARCH_FAILED_WORDS = "search failed · showing all notes"
    }
}

/**
 * PURE home-grid projection (H1): archived notes leave the home surface —
 * the Archive screen keeps showing them via [com.piercingxx.xxnote.ui.archive.ArchiveFilter] —
 * then most-recently-updated first with id descending as the deterministic
 * tie-break (listLive() returns creation order; recency matches Keep's muscle
 * memory), split into Pinned and Others.
 */
internal fun projectHome(cards: List<NoteCard>): Pair<List<NoteCard>, List<NoteCard>> {
    val sorted = cards.asSequence()
        .filterNot { it.archived }
        .sortedWith(compareByDescending<NoteCard> { it.updatedAtMillis }.thenByDescending { it.id })
        .toList()
    return sorted.filter { it.pinned } to sorted.filterNot { it.pinned }
}

/**
 * PURE L7 fallback for a refused FTS query: every live note as a card,
 * archived excluded (H1), input order preserved.
 */
internal fun searchFallbackCards(liveNotes: List<LocalNote>): List<NoteCard> =
    liveNotes.map { buildNoteCard(it.id, it.wholeFileText) }.filterNot { it.archived }
