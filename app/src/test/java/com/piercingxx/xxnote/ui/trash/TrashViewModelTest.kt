package com.piercingxx.xxnote.ui.trash

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.data.VaultStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * M5 gate: a trailing refresh must preserve `notice` (and `busy`) exactly
 * like the GridViewModel precedent — a failed restore's plain words used to
 * be wiped by the very refresh that followed it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashViewModelTest {

    private lateinit var vm: TrashViewModel

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>().also {
            java.io.File(it.filesDir, VaultStore.MIRROR_DIR).deleteRecursively()
        }
        vm = TrashViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
        awaitState { !it.loading }
    }

    /** Drains the main-looper queue until [pred] holds or the deadline passes. */
    private fun awaitState(
        deadlineMs: Long = 8_000,
        pred: (TrashViewModel.UiState) -> Boolean,
    ): TrashViewModel.UiState {
        val deadline = System.currentTimeMillis() + deadlineMs
        var state = vm.state.value
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            state = vm.state.value
            if (pred(state)) return state
            Thread.sleep(20)
        }
        return state
    }

    @Test
    fun failedRestoreWordsSurviveTheTrailingRefresh() {
        assertTrue(vm.state.value.rows.isEmpty())

        vm.restore("not-a-known-id")

        val afterFailure = awaitState { !it.busy && it.notice != null }
        assertEquals(TrashViewModel.RESTORE_FAILED_WORDS, afterFailure.notice)

        // The refresh that follows every action — and every ON_RESUME — must
        // keep the words standing (M5).
        vm.refresh()
        val afterRefresh = awaitState { !it.loading }
        assertEquals(TrashViewModel.RESTORE_FAILED_WORDS, afterRefresh.notice)

        vm.refresh()
        val afterSecondRefresh = awaitState { !it.loading }
        assertEquals(TrashViewModel.RESTORE_FAILED_WORDS, afterSecondRefresh.notice)
    }

    @Test
    fun successfulRefreshKeepsRowsAndStaysQuietWhenNothingFailed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        VaultStore(context).let { store ->
            val id = com.piercingxx.xxnote.core.Ulid.generate()
            store.write(id, "---\nid: $id\ntitle: Doomed\n---\nbody\n")
            store.trash(id)
        }

        vm.refresh()
        val state = awaitState { !it.loading && it.rows.isNotEmpty() }
        assertEquals(null, state.notice)
        assertEquals(1, state.rows.size)
    }
}
