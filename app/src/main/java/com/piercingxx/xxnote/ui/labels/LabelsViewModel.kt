package com.piercingxx.xxnote.ui.labels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.sync.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WS8 label-management state holder (design §12 item 3): the aggregated list
 * of labels in use, plus create / rename / delete — rename and delete
 * PROPAGATE across every live note carrying the label by rewriting its
 * frontmatter through [LabelOps] and writing via [VaultStore.write].
 *
 * All vault I/O rides [Dispatchers.IO]; [VaultStore]'s synchronous ports are
 * never invoked from the main thread. Propagation reads one [VaultStore.listLive]
 * snapshot and writes each affected note; a note that fails keeps its bytes
 * (temp-then-rename, §15) and the first failure surfaces in plain words via
 * [UiState.notice]. Every completed propagation enqueues an expedited sync so
 * the changes leave the device.
 */
class LabelsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val labels: List<LabelUsage> = emptyList(),
        /** True while a rename/delete propagation is in flight. */
        val busy: Boolean = false,
        /** Plain words for the last failed read/propagation or rejected input. */
        val notice: String? = null,
    )

    private val context get() = getApplication<Application>()

    private val store by lazy { VaultStore(context) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-scans the mirror and re-aggregates; cheap, run on (re)entry (D1). */
    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { load() }
        }
    }

    /**
     * M5: a trailing refresh preserves `busy` and `notice` exactly like the
     * GridViewModel precedent — propagate's failure words and an in-flight
     * flag must survive the re-scan that follows every action (and every
     * ON_RESUME), or failure notices would self-destruct instantly.
     */
    private fun load(): UiState {
        val previous = _state.value
        return try {
            UiState(
                loading = false,
                labels = aggregateLabelUsage(store.listLive().map { it.id to it.wholeFileText }),
                busy = previous.busy,
                notice = previous.notice,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UiState(
                loading = false,
                labels = previous.labels,
                busy = previous.busy,
                notice = READ_FAILED_WORDS + reasonOf(e),
            )
        }
    }

    /**
     * Validate a create-field draft. Labels exist ONLY in use (§12 item 3),
     * so an accepted create assigns nothing and persists nothing — the field
     * clears and the list stays truthful. Returns true when accepted (the
     * screen may clear the draft); rejection lands in [UiState.notice].
     */
    fun create(rawName: String): Boolean {
        val name = try {
            LabelOps.normalize(rawName)
        } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(notice = e.message)
            return false
        }
        val existing = _state.value.labels.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            _state.value = _state.value.copy(notice = "'${existing.name}' is already in use")
            return false
        }
        _state.value = _state.value.copy(notice = null)
        return true
    }

    /** Inline rename with propagation across ALL notes containing `fromRaw`. */
    fun rename(fromRaw: String, toRaw: String) {
        if (_state.value.busy) return
        val from = valid(fromRaw) ?: return
        val to = valid(toRaw) ?: return
        propagate(target = from) { text, name -> LabelOps.renameLabel(text, name, to) }
    }

    /** Untag EVERY note carrying this label (count shown in the confirm dialog). */
    fun delete(rawName: String) {
        if (_state.value.busy) return
        val name = valid(rawName) ?: return
        propagate(target = name) { text, name -> LabelOps.removeLabel(text, name) }
    }

    private fun valid(raw: String): String? = try {
        LabelOps.normalize(raw)
    } catch (e: IllegalArgumentException) {
        _state.value = _state.value.copy(notice = e.message)
        null
    }

    /**
     * One scan, then per-note rewrite+write for every live note whose labels
     * contain [target] (case-insensitive). First failure wins the notice;
     * remaining notes are still attempted — partial progress beats none.
     */
    private fun propagate(target: String, edit: (wholeFileText: String, target: String) -> String) {
        _state.value = _state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                var firstError: String? = null
                for (note in store.listLive()) {
                    val doc = Frontmatter.parse(note.wholeFileText)
                    if (!LabelOps.hasLabel(doc.labels, target)) continue
                    try {
                        store.write(note.id, edit(note.wholeFileText, target))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (firstError == null) firstError = reasonOf(e)
                    }
                }
                try {
                    SyncWorker.enqueueExpedited(context)
                } catch (_: Exception) {
                    // Sync is how label edits reach other devices; their
                    // absence never un-applies them locally.
                }
                firstError
            }
            _state.value = _state.value.copy(
                busy = false,
                notice = failure?.let { words -> PARTIAL_WORDS + words },
            )
            refresh()
        }
    }

    private fun reasonOf(e: Exception): String = e.message ?: e.javaClass.simpleName

    companion object {
        /** §15 tone: plain words, one line, until the next success clears them. */
        internal const val READ_FAILED_WORDS = "couldn't read the vault · "
        internal const val PARTIAL_WORDS = "some notes couldn't be updated · "
    }
}
