package com.piercingxx.xxnote.ui.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.sync.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WS8 trash state holder (design §12 item 3, D9/D10). The trash folder is
 * re-listed on every resume (D1: file wins) and each whole-file text is mapped
 * to a [TrashRow] by the PURE [buildTrashRow] with the load instant as `now`.
 * All disk/Room access rides [Dispatchers.IO]; [VaultStore]'s synchronous port
 * contract is never invoked from the main thread.
 *
 * D9 ruling, enforced by absence: there is NO manual purge anywhere on this
 * surface. Expiry after 7 days is the only path that truly deletes; this class
 * offers restore and nothing else.
 */
class TrashViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val rows: List<TrashRow> = emptyList(),
        /** True while a restore is in flight; one tap, one restore. */
        val busy: Boolean = false,
        /** Plain words for the last failed restore; kept until the next attempt. */
        val notice: String? = null,
    )

    private val context get() = getApplication<Application>()

    private val store by lazy { VaultStore(context) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-scans `.xxnote/trash/` and rebuilds every row; cheap, run on (re)entry. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    private fun load(): UiState {
        val previous = _state.value
        return try {
            val now = System.currentTimeMillis()
            // Ruling beyond spec: soonest-to-expiry first (ascending trashedAt),
            // unknown stamps LAST (they render "—", never guessed), id ascending as
            // the deterministic tie-break. Urgency first matches the chip's job.
            val rows = store.listTrashed()
                .map { buildTrashRow(it.id, it.wholeFileText, now) }
                .sortedWith(
                    compareBy<TrashRow> { it.trashedAtMillis ?: Long.MAX_VALUE }.thenBy { it.id },
                )
            // M5: preserve busy + notice like the GridViewModel precedent —
            // a failed restore's words must survive the refresh that follows.
            UiState(loading = false, rows = rows, busy = previous.busy, notice = previous.notice)
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
     * Restore (§6 row 8/10 spirit): strip `trashedAt:` via [VaultStore.restore]
     * — a move back to the vault, never an unlink (D9) — then enqueue an
     * expedited sync so peers converge fast. The sync enqueue failing (offline,
     * WorkManager refused) never undoes the vault move; it retries on schedule.
     */
    fun restore(id: String) {
        if (_state.value.busy) return // swallow double-taps before they double-move
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val words = withContext(Dispatchers.IO) {
                try {
                    store.restore(id)
                    try {
                        SyncWorker.enqueueExpedited(context)
                    } catch (_: Exception) {
                        // Sync spreads the restore; its absence never un-restores.
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    RESTORE_FAILED_WORDS
                }
            }
            _state.update { it.copy(busy = false, notice = words) }
            refresh()
        }
    }

    companion object {
        internal const val RESTORE_FAILED_WORDS =
            "couldn't restore — storage problem · the note is still in trash"
        internal const val LIST_FAILED_WORDS =
            "couldn't read the trash folder · nothing was changed"
    }
}
