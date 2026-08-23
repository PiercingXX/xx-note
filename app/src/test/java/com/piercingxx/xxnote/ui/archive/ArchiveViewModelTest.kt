package com.piercingxx.xxnote.ui.archive

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.data.VaultStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * M5 gate: the archive surface's trailing refresh must preserve `notice` and
 * `busy` exactly like the GridViewModel precedent — an unarchive failure's
 * words used to be wiped by the refresh that followed it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveViewModelTest {

    private lateinit var context: android.content.Context
    private lateinit var vm: ArchiveViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, VaultStore.MIRROR_DIR).deleteRecursively()
        vm = ArchiveViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
        awaitState { !it.loading }
    }

    /** Drains the main-looper queue until [pred] holds or the deadline passes. */
    private fun awaitState(
        deadlineMs: Long = 8_000,
        pred: (ArchiveViewModel.UiState) -> Boolean,
    ): ArchiveViewModel.UiState {
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

    private fun seedArchivedNote(): String {
        val id = Ulid.generate()
        VaultStore(context).write(
            id,
            "---\nid: $id\ntitle: Resting\narchived: true\n---\nbody\n",
        )
        return id
    }

    @Test
    fun failedUnarchiveWordsSurviveTheTrailingRefresh() {
        vm.unarchive("not-a-known-id")

        val afterFailure = awaitState { !it.busy && it.notice != null }
        assertEquals(ArchiveViewModel.ALREADY_GONE_WORDS, afterFailure.notice)

        // M5: the refresh after the action keeps the words standing.
        vm.refresh()
        assertEquals(ArchiveViewModel.ALREADY_GONE_WORDS, awaitState { !it.loading }.notice)
    }

    @Test
    fun archivedNotesAreListedAndUnarchiveClearsThemQuietly() {
        val id = seedArchivedNote()
        vm.refresh()
        val listed = awaitState { !it.loading && it.rows.isNotEmpty() }
        assertEquals(id, listed.rows.single().id)
        assertEquals(null, listed.notice)

        vm.unarchive(id)
        val after = awaitState { !it.busy && it.rows.isEmpty() }
        assertEquals(null, after.notice)
    }
}
