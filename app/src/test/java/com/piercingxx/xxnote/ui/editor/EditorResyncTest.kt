package com.piercingxx.xxnote.ui.editor

import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.data.VaultStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §15 editor-resync gate + the generation seeding contract, at the view-model
 * level (Robolectric: real vault mirror + real Room file db under filesDir,
 * exactly the production wiring the lazy [EditorViewModel] store resolves to;
 * helpers poll with a deadline like [EditorViewModelTest]).
 *
 * - Generation seeding (stale-reseed bug): typing refreshes
 *   [EditorViewModel.UiState.initialTitle]/[initialBody] WITHOUT bumping
 *   [EditorViewModel.UiState.generation], so a recreation (composition reborn,
 *   view model surviving) re-seeds its fields from the CURRENT buffer — never
 *   first-load text — and the next keystroke persists both edits.
 * - Resume resync (open editor vs background pull): ON_RESUME re-reads the
 *   mirror and compares BYTES against the load-time snapshot. Clean+unchanged
 *   and dirty+unchanged are no-ops; clean+moved adopts; dirty+moved merges
 *   through core's Diff3 (plain) or ChecklistMerge (`type: checklist`, NEVER
 *   line-wise); unmergeable results FORK so both sides stay readable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorResyncTest {

    private lateinit var context: android.content.Context
    private lateinit var store: VaultStore
    private lateinit var vm: EditorViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, VaultStore.MIRROR_DIR).deleteRecursively()
        store = VaultStore(context)
        vm = EditorViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
    }

    /** Polls until [pred] holds or the deadline passes; returns the last state. */
    private fun await(deadlineMs: Long = 8_000, pred: (EditorViewModel.UiState) -> Boolean): EditorViewModel.UiState {
        val deadline = System.currentTimeMillis() + deadlineMs
        var state = vm.state.value
        while (System.currentTimeMillis() < deadline) {
            state = vm.state.value
            if (pred(state)) return state
            Thread.sleep(25)
        }
        return state
    }

    private fun noteText(
        id: String,
        title: String,
        body: String,
        type: String? = null,
    ): String = "---\nid: $id\ntitle: $title\ncreated: 2026-08-20T09:00:00Z\n" +
        "updated: 2026-08-21T10:00:00Z\npinned: false\narchived: false\n" +
        (if (type != null) "type: $type\n" else "") +
        "---\n$body"

    private fun bodyOf(id: String): String = Frontmatter.parse(store.read(id)!!.wholeFileText).bodyText

    // ---- Generation seeding (stale-reseed bug) --------------------------------

    /**
     * Typing must refresh the screen-text mirrors without bumping the
     * generation: a recreated composition re-runs its generation-keyed seeding
     * effect once and copies these CURRENT values — so after recreation the
     * next keystroke pushes what the user actually sees into the save
     * pipeline, and BOTH edits land in the file, not a derivative of first
     * load.
     */
    @Test
    fun typingKeepsScreenTextCurrentWithoutBumpingGeneration() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Seed", "first load\n"))
        vm.load(id)
        val loaded = await { it.ready }
        assertEquals("first load\n", loaded.initialBody)
        val generationAtLoad = loaded.generation

        vm.onBodyChange("first load plus a typed line\n")
        assertEquals("typing must not bump the generation", generationAtLoad, vm.state.value.generation)
        assertEquals("first load plus a typed line\n", vm.state.value.initialBody)

        vm.onTitleChange("Typed title")
        assertEquals(generationAtLoad, vm.state.value.generation)
        assertEquals("Typed title", vm.state.value.initialTitle)

        // A recreation replays the seeding closure against this unchanged
        // generation; it would copy exactly these values. Persisting proves
        // the buffer they mirror is real.
        vm.flushPendingSave()

        val whole = store.read(id)!!.wholeFileText
        assertEquals("Typed title", Frontmatter.parse(whole).title)
        assertEquals("first load plus a typed line\n", Frontmatter.parse(whole).bodyText)
        assertNull(vm.state.value.saveError)
    }

    // ---- Resume resync -----------------------------------------------------------

    /** Clean buffer + a background pull rewriting the mirror → adopt on resume. */
    @Test
    fun cleanBufferAdoptsPulledBytesAndBumpsGeneration() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Local draft", "original bytes\n"))
        vm.load(id)
        val loaded = await { it.ready }
        val generationAtLoad = loaded.generation

        // Exactly what a SyncEngine pull does: rewrite the mirror under the id.
        val pulled = noteText(id, "Renamed remotely", "pulled remote bytes\n")
        store.write(id, pulled)

        vm.resyncFromDisk()

        val adopted = await { it.generation > generationAtLoad }
        assertEquals("pulled remote bytes\n", adopted.initialBody)
        assertEquals("Renamed remotely", adopted.initialTitle)
        assertNull(adopted.saveError)
        assertFalse(vm.hasUnsavedEdits())
        assertEquals("adoption must not rewrite the pulled bytes", pulled, store.read(id)!!.wholeFileText)
    }

    /** Clean buffer + disk unmoved → strict no-op: no churn, no reseed. */
    @Test
    fun resumeWithUnchangedDiskIsANoOp() {
        val id = Ulid.generate()
        val original = noteText(id, "Still", "nothing moved\n")
        store.write(id, original)
        vm.load(id)
        await { it.ready }
        val generationAtLoad = vm.state.value.generation

        vm.resyncFromDisk()

        Thread.sleep(400)
        assertEquals(original, store.read(id)!!.wholeFileText)
        assertEquals(generationAtLoad, vm.state.value.generation)
        assertFalse(vm.hasUnsavedEdits())
    }

    /** Dirty buffer + disk unmoved → no-op; the debounced pipeline continues. */
    @Test
    fun dirtyBufferWithUnchangedDiskLeavesTheDebounceAlone() {
        val id = Ulid.generate()
        val original = noteText(id, "In flight", "typed during flight pending\n")
        store.write(id, original)
        vm.load(id)
        await { it.ready }
        val generationAtLoad = vm.state.value.generation

        vm.onBodyChange("typed during flight, still pending\n")
        vm.resyncFromDisk()

        Thread.sleep(400) // well inside the 800 ms window
        assertEquals(generationAtLoad, vm.state.value.generation)
        assertTrue(vm.hasUnsavedEdits())

        vm.flushPendingSave()
        await { _ -> store.read(id)!!.wholeFileText.contains("still pending") }
        assertTrue(bodyOf(id).contains("typed during flight"))
    }

    /**
     * Dirty buffer + moved disk, plain prose: both sides' line edits survive
     * the three-way merge (base = load snapshot, ours = buffer, theirs =
     * disk), and the merged bytes land under the original id.
     */
    @Test
    fun dirtyPlainBodyMergesBothSidesOnResume() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Trip", "line one\nline two\nline three\n"))
        vm.load(id)
        await { it.ready }
        val generationAtLoad = vm.state.value.generation

        vm.onBodyChange("line one edited\nline two\nline three\n")
        store.write(id, noteText(id, "Trip", "line one\nline two\nline three remote\n"))

        vm.resyncFromDisk()

        val merged = await { !vm.hasUnsavedEdits() && vm.state.value.saveError == null }
        val whole = store.read(id)!!.wholeFileText
        val body = Frontmatter.parse(whole).bodyText
        assertTrue("local edit lost: $body", body.contains("line one edited"))
        assertTrue("remote edit lost: $body", body.contains("line three remote"))
        assertTrue("untouched line moved: $body", body.contains("line two"))
        assertTrue("merge must bump the generation for a reseed", merged.generation > generationAtLoad)
        assertEquals(body, merged.initialBody)
    }

    /**
     * Dirty buffer + moved disk on a `type: checklist` body: the merge is
     * ITEM-wise through core ChecklistMerge — an item removed locally while
     * edited remotely keeps the EDIT. Line-wise Diff3 would call this a
     * conflict and fork; item-wise lands one cleanly merged file.
     */
    @Test
    fun dirtyChecklistBodyMergesItemWiseNotLineWise() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Chores", "- [ ] alpha\n- [ ] beta\n", type = "checklist"))
        vm.load(id)
        await { it.ready }

        vm.onBodyChange("- [ ] beta\n") // local removed the alpha item
        store.write(id, noteText(id, "Chores", "- [ ] alpha grew\n- [ ] beta\n", type = "checklist"))

        vm.resyncFromDisk()

        await { !vm.hasUnsavedEdits() && vm.state.value.saveError == null }
        val body = bodyOf(id)
        assertTrue("remote item edit lost: $body", body.contains("- [ ] alpha grew"))
        assertTrue(body.contains("- [ ] beta"))
        assertFalse("the stale pre-edit item must be gone: $body", body.contains("- [ ] alpha\n"))
        // Item-wise convergence, not a fork: exactly one live note, original id.
        assertEquals(1, store.listLive().size)
        assertEquals(id, store.listLive().single().id)
    }

    /**
     * Unmergeable prose (both sides rewrote the same line): FORK, never
     * overwrite. The remote bytes become their own conflict-stamped note
     * beside the original, whose pipeline keeps the local side — both sides
     * readable afterwards.
     */
    @Test
    fun unmergeableProseForksInsteadOfOverwriting() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Crossed", "shared line\n"))
        vm.load(id)
        await { it.ready }

        vm.onBodyChange("shared local edit\n")
        store.write(id, noteText(id, "Crossed", "shared remote edit\n"))

        vm.resyncFromDisk()

        await { store.listLive().size == 2 }
        val fork = store.listLive().first { it.id != id }
        val forkDoc = Frontmatter.parse(fork.wholeFileText)
        assertEquals(id, forkDoc.conflictOf)
        assertTrue(
            "the remote side must rest in the fork",
            forkDoc.bodyText.contains("shared remote edit"),
        )

        // The local side persists as usual under the original id.
        vm.flushPendingSave()
        await { _ -> bodyOf(id).contains("shared local edit") }
        assertEquals(id, Frontmatter.parse(store.read(id)!!.wholeFileText).id)
    }

    /** A note trashed underneath the open editor closes it, like an unknown id. */
    @Test
    fun externallyTrashedNoteClosesOnResume() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Doomed", "gone underneath me\n"))
        vm.load(id)
        await { it.ready }

        store.trash(id)
        vm.resyncFromDisk()

        await { it.missing }
    }

    // ---- Fork-path joins (review S1: no cancel-without-join) -------------------

    /**
     * The inversion corner with a persist MID-FLIGHT: the remote fork write
     * fails once, the local side forks instead, and the debounced persist is
     * blocked inside its write when the inversion adopts. The join must wait
     * that persist out and then RESTORE the pulled bytes to the mirror — a
     * bare cancel let the stale persist land last, leaving disk = stale local
     * text while memory claimed the remote bytes (a readable-bytes loss on
     * process death, and a stale push on the next sync).
     */
    @Test
    fun inversionJoinsAMidFlightPersistAndRestoresThePulledBytes() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Crossed", "shared line\n"))
        vm.load(id)
        await { it.ready }

        val persistEntered = CountDownLatch(1)
        val releasePersist = CountDownLatch(1)
        var failedForkOnce = false
        vm.writeThrough = { writeId, text ->
            if (writeId == id) {
                // The debounced persist (and any later original-slot write)
                // parks here: a mid-flight write the resync must join.
                persistEntered.countDown()
                releasePersist.await(8, TimeUnit.SECONDS)
                store.write(writeId, text)
            } else {
                if (!failedForkOnce) {
                    failedForkOnce = true
                    throw IOException("transient wedge")
                }
                store.write(writeId, text)
            }
        }

        vm.onBodyChange("shared local edit\n")
        assertTrue(
            "persist never reached its write",
            persistEntered.await(8, TimeUnit.SECONDS),
        )
        // Flag is still raised: the write has not returned yet.
        assertTrue(vm.hasUnsavedEdits())

        // The background pull lands UNDER the mid-flight persist…
        val pulled = noteText(id, "Crossed", "shared remote edit\n")
        store.write(id, pulled)
        // …and the resume finds dirty buffer + moved disk, unmergeable.
        vm.resyncFromDisk()

        // The local side forks (the first remote-fork attempt failed).
        await { store.listLive().size == 2 }
        // Let the parked persist finish — it writes stale local text over the
        // pulled bytes, exactly the clobber the join-then-restore must undo.
        releasePersist.countDown()

        await {
            !vm.hasUnsavedEdits() && vm.state.value.saveError == null &&
                store.read(id)!!.wholeFileText == pulled
        }
        assertTrue(
            "mirror must hold the RESTORED pulled bytes, got: ${store.read(id)!!.wholeFileText}",
            store.read(id)!!.wholeFileText == pulled,
        )
        assertEquals("memory adopts the remote bytes", "shared remote edit\n", vm.state.value.initialBody)

        val fork = store.listLive().first { it.id != id }
        val forkDoc = Frontmatter.parse(fork.wholeFileText)
        assertEquals(id, forkDoc.conflictOf)
        assertTrue(forkDoc.bodyText.contains("shared local edit"))
    }

    /**
     * Storage wedged for FOREIGN ids twice (first fork + inversion both
     * refused): the fix retries the remote fork ONCE after joining the armed
     * debounce out. The retry lands, the pulled bytes stay untouched at the
     * original slot, the local side stays dirty for the next flush, and the
     * failure speaks — nothing silently tidied away.
     */
    @Test
    fun doubleForkFailureRetriesTheRemoteForkOnceAndKeepsDirtRaised() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Crossed", "shared line\n"))
        vm.load(id)
        await { it.ready }

        var foreignFailures = 0
        vm.writeThrough = { writeId, text ->
            if (writeId != id && foreignFailures < 2) {
                foreignFailures++
                throw IOException("storage wedged")
            }
            store.write(writeId, text)
        }

        vm.onBodyChange("shared local edit\n")
        val pulled = noteText(id, "Crossed", "shared remote edit\n")
        store.write(id, pulled)
        vm.resyncFromDisk()

        // Third foreign write (the retry) succeeds: exactly one fork.
        await { store.listLive().size == 2 }
        val fork = store.listLive().first { it.id != id }
        val forkDoc = Frontmatter.parse(fork.wholeFileText)
        assertEquals("the retried fork carries the remote side", id, forkDoc.conflictOf)
        assertTrue(forkDoc.bodyText.contains("shared remote edit"))

        // Original slot untouched; local dirt intact for the next flush.
        // (No words yet: the retry LANDED, so nothing was lost to speak of.)
        assertEquals(pulled, store.read(id)!!.wholeFileText)
        assertTrue(vm.hasUnsavedEdits())

        // The next flush persists the local side beside the preserved fork.
        vm.flushPendingSave()
        await { _ -> bodyOf(id).contains("shared local edit") }
        assertEquals(2, store.listLive().size)
    }

    /**
     * Merge write fails after the joined persist may have already replaced
     * the pulled bytes on disk with the local buffer: the pulled bytes are
     * parked as their own note so they stay readable, dirt stays raised.
     */
    @Test
    fun mergeWriteFailureParksThePulledBytesAsAFork() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Both", "line one\nline two\n"))
        vm.load(id)
        await { it.ready }

        vm.onBodyChange("line one edited\nline two\n")
        val pulled = noteText(id, "Both", "line one\nline two remote\n")
        store.write(id, pulled)

        vm.writeThrough = { writeId, text ->
            if (writeId == id) throw IOException("disk full")
            store.write(writeId, text)
        }
        vm.resyncFromDisk()

        await { store.listLive().size == 2 && vm.state.value.saveError != null }
        val fork = store.listLive().first { it.id != id }
        val forkDoc = Frontmatter.parse(fork.wholeFileText)
        assertEquals(id, forkDoc.conflictOf)
        assertTrue(
            "pulled bytes must rest in the parked fork, got: ${forkDoc.bodyText}",
            forkDoc.bodyText.contains("line two remote"),
        )
        assertTrue(vm.hasUnsavedEdits())
        assertTrue(vm.state.value.saveError!!.startsWith(EditorViewModel.NOT_SAVED_WORDS))
    }
}
