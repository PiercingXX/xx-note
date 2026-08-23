package com.piercingxx.xxnote.ui.grid

/**
 * PURE state machine for the top-bar search field (WS8; design §12 item 1).
 *
 * The field starts COLLAPSED (a search affordance in the top bar). Expanding
 * it opens the editor-with-focus treatment but does NOT search — an empty
 * query never touches the index. Typing records the raw text verbatim (the
 * sanitized FTS projection happens at query time, see [SearchQuery]).
 * COLLAPSING always resets the query: leaving search must restore the normal
 * Pinned/Others grid exactly, with no stale result set behind it.
 *
 * R4 lives here by contract: search runs purely against the local Room cache
 * (`note_fts`), NEVER the network. Nothing in this file's call graph reaches
 * [com.piercingxx.xxnote.sync] transport code; the only sync touchpoint in
 * the grid remains explicit batch/capture writes enqueuing a worker.
 */
data class SearchFieldState(
    val open: Boolean = false,
    val raw: String = "",
) {
    /** True when the grid should be showing search results, not sections. */
    val isActive: Boolean get() = open && raw.isNotBlank()
}

sealed interface SearchFieldEvent {
    data object Expand : SearchFieldEvent
    data object Collapse : SearchFieldEvent
    data class SetQuery(val raw: String) : SearchFieldEvent
}

/** Total reducer: every (state, event) pair yields the next state. */
fun reduceSearchField(state: SearchFieldState, event: SearchFieldEvent): SearchFieldState =
    when (event) {
        SearchFieldEvent.Expand -> state.copy(open = true)
        SearchFieldEvent.Collapse -> SearchFieldState(open = false, raw = "")
        is SearchFieldEvent.SetQuery -> state.copy(open = true, raw = event.raw)
    }

/** Debounce window between keystroke and FTS query (design: ~250 ms). */
const val SEARCH_DEBOUNCE_MS = 250L
