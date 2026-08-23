package com.piercingxx.xxnote.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One named test per §6 row (design.md), one per pinned totality corner
 * (core/README.md + WS2 task rulings), and the row-12 rejection ladder.
 * Realistic note bodies throughout; no timestamps anywhere (todo rule #3).
 */
class SyncPolicyTest {

    // -- fixtures --------------------------------------------------------------
    private val baseBody =
        "# Trip packing\n\n- passport\n- charger\n- headphones\n"
    private val localEdit =
        "# Trip packing\n\n- passport\n- charger\n- headphones\n- travel adapter\n"
    private val remoteEdit =
        "# Trip packing\n\n- passport\n- charger\n- headphones\n- kindle\n"

    private fun base() = BaseSnapshot(baseBody, etag = "\"a1\"")
    private fun live(body: String) = NoteState(body = body, trashed = false)
    private fun trashed(body: String) = NoteState(body = body, trashed = true)
    private fun absent() = NoteState(body = null, trashed = false)

    // -- §6 rows ----------------------------------------------------------------

    @Test
    fun row01_pull() {
        // Fresh note seen on the server for the first time.
        assertEquals(
            Verdict.Pull,
            SyncPolicy.decide(base = null, local = absent(), remote = live(remoteEdit)),
        )
    }

    @Test
    fun row02_push() {
        // Note drafted offline, server has never heard of it.
        assertEquals(
            Verdict.Push,
            SyncPolicy.decide(base = null, local = live(localEdit), remote = absent()),
        )
    }

    @Test
    fun row03_nothing_clean_clean() {
        assertEquals(
            Verdict.Nothing,
            SyncPolicy.decide(base(), live(baseBody), live(baseBody)),
        )
    }

    @Test
    fun row04_push_local_dirty_remote_clean() {
        assertEquals(
            Verdict.Push,
            SyncPolicy.decide(base(), live(localEdit), live(baseBody)),
        )
    }

    @Test
    fun metadata_only_edit_pushes() {
        // H2 pin: `body` is WHOLE-FILE text — frontmatter plus Markdown. The
        // prose region is byte-identical in all three snapshots; only the
        // frontmatter region moved. A metadata-only edit must dirty the
        // snapshot and fire row 4, never silently evaluate clean+clean →
        // Nothing and stop metadata (labels/pin/color) from propagating.
        val baseFile =
            "---\nid: 01J8ZM9Q2X7P4T6W9K2M3N5V8A\npinned: false\n---\n\n# Trip packing\n\n- passport\n"
        val metaEdited =
            "---\nid: 01J8ZM9Q2X7P4T6W9K2M3N5V8A\npinned: true\n---\n\n# Trip packing\n\n- passport\n"
        assertEquals(
            Verdict.Push,
            SyncPolicy.decide(BaseSnapshot(baseFile), live(metaEdited), live(baseFile)),
        )
    }

    @Test
    fun row05_pull_local_clean_remote_dirty() {
        assertEquals(
            Verdict.Pull,
            SyncPolicy.decide(base(), live(baseBody), live(remoteEdit)),
        )
    }

    @Test
    fun row06_merge_both_dirty() {
        assertEquals(
            Verdict.Merge,
            SyncPolicy.decide(base(), live(localEdit), live(remoteEdit)),
        )
    }

    @Test
    fun row07_trash_local_trashed_remote_clean() {
        assertEquals(
            Verdict.Trash,
            SyncPolicy.decide(base(), trashed(baseBody), live(baseBody)),
        )
    }

    @Test
    fun row08_resurrect_local_trashed_remote_dirty() {
        assertEquals(
            Verdict.Resurrect,
            SyncPolicy.decide(base(), trashed(baseBody), live(remoteEdit)),
        )
    }

    @Test
    fun row09_trash_local_clean_remote_gone_base_existed() {
        assertEquals(
            Verdict.Trash,
            SyncPolicy.decide(base(), live(baseBody), absent()),
        )
    }

    @Test
    fun row10_resurrect_local_dirty_remote_gone_base_existed() {
        assertEquals(
            Verdict.Resurrect,
            SyncPolicy.decide(base(), live(localEdit), absent()),
        )
    }

    @Test
    fun row11_fork_no_base_differing_bodies() {
        assertEquals(
            Verdict.Fork,
            SyncPolicy.decide(base = null, local = live(localEdit), remote = live(remoteEdit)),
        )
    }

    @Test
    fun row12_replan_terminal() {
        // Rounds 0–2 stay in the plan loop; round 3 is terminal and forks.
        assertEquals(Verdict.Replan, SyncPolicy.decidePushRejection(roundsCompleted = 2))
        assertEquals(Verdict.Fork, SyncPolicy.decidePushRejection(roundsCompleted = 3))
    }

    @Test
    fun push_rejection_boundary() {
        assertEquals(Verdict.Replan, SyncPolicy.decidePushRejection(0))
        assertEquals(Verdict.Replan, SyncPolicy.decidePushRejection(1))
        assertEquals(Verdict.Replan, SyncPolicy.decidePushRejection(2))
        assertEquals(Verdict.Fork, SyncPolicy.decidePushRejection(3))
        // Terminal stays terminal however late the engine reports.
        assertEquals(Verdict.Fork, SyncPolicy.decidePushRejection(4))
        assertEquals(Verdict.Fork, SyncPolicy.decidePushRejection(50))
    }

    // -- totality corners (core/README.md + task rulings) ------------------------

    @Test
    fun corner_both_absent_nothing() {
        assertEquals(Verdict.Nothing, SyncPolicy.decide(null, absent(), absent()))
        assertEquals(Verdict.Nothing, SyncPolicy.decide(base(), absent(), absent()))
    }

    @Test
    fun corner_no_base_local_trashed_remote_present_fork() {
        // Two claims on one id and no ancestor to arbitrate: fork, keep both.
        assertEquals(
            Verdict.Fork,
            SyncPolicy.decide(null, trashed(baseBody), live(remoteEdit)),
        )
    }

    @Test
    fun corner_no_base_both_present_equal_bodies_nothing() {
        // Engine adopts the agreed body as base; nothing moves.
        assertEquals(
            Verdict.Nothing,
            SyncPolicy.decide(null, live(baseBody), live(baseBody)),
        )
    }

    @Test
    fun corner_local_trashed_remote_gone_base_existed_nothing() {
        // Deletion already agreed; the trashed copy keeps its bytes where it is.
        assertEquals(
            Verdict.Nothing,
            SyncPolicy.decide(base(), trashed(localEdit), absent()),
        )
    }

    @Test
    fun corner_local_trashed_remote_gone_no_base_nothing() {
        assertEquals(
            Verdict.Nothing,
            SyncPolicy.decide(null, trashed(localEdit), absent()),
        )
    }

    @Test
    fun corner_local_absent_remote_trashed_trash() {
        // Task-pinned mirror of row 9: the note lives in no live vault; the only
        // holder of bytes is the remote trash copy. Trash confirms that state and
        // never unlinks (D9).
        assertEquals(Verdict.Trash, SyncPolicy.decide(null, absent(), trashed(remoteEdit)))
        assertEquals(Verdict.Trash, SyncPolicy.decide(base(), absent(), trashed(remoteEdit)))
    }
    @Test
    fun corner_local_clean_remote_trashed_trash() {
        // Row 9's true mirror via the README rule that remote.trashed counts as
        // gone from the live vault: local still holds the last-agreed copy, so
        // it follows the deletion into trash.
        assertEquals(
            Verdict.Trash,
            SyncPolicy.decide(base(), live(baseBody), trashed(baseBody)),
        )
    }

    @Test
    fun corner_local_dirty_remote_trashed_resurrect() {
        // Row 10's mirror: an edit outranks a delete even when the delete is
        // already sitting in the far side's trash.
        assertEquals(
            Verdict.Resurrect,
            SyncPolicy.decide(base(), live(localEdit), trashed(baseBody)),
        )
    }

    @Test
    fun corner_local_trashed_remote_trashed_nothing() {
        assertEquals(
            Verdict.Nothing,
            SyncPolicy.decide(base(), trashed(localEdit), trashed(remoteEdit)),
        )
        assertEquals(
            Verdict.Nothing,
            SyncPolicy.decide(null, trashed(localEdit), trashed(remoteEdit)),
        )
    }

    // -- corner rulings beyond the README list (documented in SyncPolicy KDoc) ---

    @Test
    fun corner_no_base_local_live_remote_trashed_push() {
        // Row 2 fires against the live vault; the remote trash copy is untouched
        // by a PUT, so nothing is lost (D9).
        assertEquals(
            Verdict.Push,
            SyncPolicy.decide(null, live(localEdit), trashed(remoteEdit)),
        )
    }

    @Test
    fun corner_base_exists_local_absent_remote_live_pull() {
        // A known base with a vanished local mirror is an anomaly (local deletes
        // go through trash, never unlink): treat the vault as truth and re-fetch.
        assertEquals(Verdict.Pull, SyncPolicy.decide(base(), absent(), live(remoteEdit)))
        assertEquals(Verdict.Pull, SyncPolicy.decide(base(), absent(), live(baseBody)))
    }

    @Test
    fun corner_base_exists_local_absent_remote_gone_nothing() {
        assertEquals(Verdict.Nothing, SyncPolicy.decide(base(), absent(), absent()))
    }
}
