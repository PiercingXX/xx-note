package com.piercingxx.xxnote.ui.labels

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
 * M5 gate for both label surfaces: a trailing refresh must preserve `notice`
 * exactly like the GridViewModel precedent. LabelsViewModel's rejected-create
 * words and LabelGridViewModel's failed-unassign words used to be wiped by
 * the very refresh that followed them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelsViewModelTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, VaultStore.MIRROR_DIR).deleteRecursively()
    }

    /** Drains the main-looper queue until [pred] holds or the deadline passes. */
    private fun <S> await(
        state: () -> S,
        deadlineMs: Long = 8_000,
        pred: (S) -> Boolean,
    ): S {
        val deadline = System.currentTimeMillis() + deadlineMs
        var s = state()
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            s = state()
            if (pred(s)) return s
            Thread.sleep(20)
        }
        return s
    }

    @Test
    fun rejectedCreateWordsSurviveTheTrailingRefresh() {
        val vm = LabelsViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
        await(state = { vm.state.value }) { !it.loading }

        vm.create("   ") // normalize refuses blank names → words

        assertEquals("label can't be empty", vm.state.value.notice)

        // M5: the refresh after the action — and any ON_RESUME — keeps them.
        vm.refresh()
        val afterRefresh = await(state = { vm.state.value }) { !it.loading }
        assertEquals("label can't be empty", afterRefresh.notice)
    }

    @Test
    fun failedUnassignWordsSurviveTheTrailingRefresh() {
        val store = VaultStore(context)
        val id = Ulid.generate()
        store.write(id, "---\nid: $id\ntitle: Tagged\nlabels: [work]\n---\nbody\n")
        val mirrorRoot = File(context.filesDir, VaultStore.MIRROR_DIR)
        val notePath = assertNotNull(store.read(id)).path

        val vm = LabelGridViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
        vm.open("work")
        await(state = { vm.state.value }) { !it.loading && it.cards.isNotEmpty() }

        // Sabotage the NEXT write: the atomic temp path is occupied by a
        // directory, so FileOutputStream must fail inside store.write.
        val planted = File(mirrorRoot, "$notePath${VaultStore.TMP_SUFFIX}")
        assertTrue(planted.mkdir())
        try {
            vm.unassign(id, "work")
            val afterFailure = await(state = { vm.state.value }) {
                !it.loading && it.notice != null
            }
            assertTrue(
                "expected unassign failure words, got: ${afterFailure.notice}",
                afterFailure.notice!!.startsWith(LabelGridViewModel.UNASSIGN_FAILED_WORDS),
            )

            // M5: the refresh after the action keeps the words standing.
            vm.refresh()
            val afterRefresh = await(state = { vm.state.value }) { !it.loading }
            assertTrue(afterRefresh.notice!!.startsWith(LabelGridViewModel.UNASSIGN_FAILED_WORDS))
        } finally {
            planted.delete()
        }
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value as T
    }
}
