package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private companion object {
        val CLOCK: Instant = Instant.parse("2026-08-23T10:04:00Z")
        const val DEVICE = "test-device"

        const val ID_A = "01J9F2K3M4N5P6Q7R8S9T0V1W2" // alpha
        const val ID_B = "01J9F2K8ZZ1A2B3C4D5E6F7G8H" // bravo
        const val ID_C = "01J9F2KA0B2C3D4E5F6G7H8J9K" // charlie
        const val ID_D = "01J9F2KB1C2D3E4F5G6H7J8M9N" // delta
    }

    private fun note(id: String, title: String, body: String): String =
        "---\nid: $id\ntitle: $title\n---\n$body"

    private fun trashedNote(id: String, title: String, body: String, stamp: String?): String =
        "---\nid: $id\ntitle: $title\n" +
            (if (stamp != null) "trashedAt: $stamp\n" else "") +
            "---\n$body"

    private fun newEngine(
        local: InMemoryLocal,
        remote: InMemoryRemote,
        book: InMemoryBook,
    ): SyncEngine = SyncEngine(local, remote, book, DEVICE, clock = { CLOCK })

    /** Seeds one fully-agreed note: mirror == remote == base, ETags consistent. */
    private fun seedAgreed(
        id: String,
        title: String,
        body: String,
        local: InMemoryLocal,
        remote: InMemoryRemote,
        book: InMemoryBook,
    ): String {
        val path = "$id-${title.lowercase()}.md"
        val text = note(id, title, body)
        local.add(id, path, text)
        remote.seed(path, text, "\"r-${id.takeLast(4)}\"")
        book.recordBase(id, text, "\"r-${id.takeLast(4)}\"")
        return path
    }

    @Test
    fun headline_disjoint_edits_merge_cleanly() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        local.add(ID_A, pathA, note(ID_A, "Alpha", "alpha local\nbeta\ngamma\n"))
        remote.seed(pathA, note(ID_A, "Alpha", "alpha\nbeta\ngamma remote\n"), "\"r1\"")
        book.recordBase(ID_A, note(ID_A, "Alpha", "alpha\nbeta\ngamma\n"), "\"r1\"")

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 1,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            outcome,
        )
        val pushedText = remote.text(pathA)
        assertNotNull(pushedText)
        assertTrue(pushedText.contains("alpha local"), "local edit lost:\n$pushedText")
        assertTrue(pushedText.contains("gamma remote"), "remote edit lost:\n$pushedText")
        assertEquals(ID_A, Frontmatter.parse(pushedText).id)
        // Base updated to the merged bytes.
        assertEquals(pushedText, book.baseOf(ID_A)?.body)
    }

    @Test
    fun same_line_conflict_forks_into_two_visible_notes() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        val originalRemoteText = note(ID_A, "Alpha", "alpha\nbeta two\ngamma\n")
        local.add(ID_A, pathA, note(ID_A, "Alpha", "alpha\nbeta one\ngamma\n"))
        remote.seed(pathA, originalRemoteText, "\"r1\"")
        book.recordBase(ID_A, note(ID_A, "Alpha", "alpha\nbeta\ngamma\n"), "\"r1\"")

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 1, trashed = 0, resurrected = 0, nothing = 0,
            ),
            outcome,
        )
        // Original keeps its id and its local bytes.
        val liveById = local.listLive().associateBy { it.id }
        val forkNote = liveById.values.first { it.id != ID_A }
        assertTrue(liveById.getValue(ID_A).wholeFileText.contains("beta one"))

        // The fork carries conflictOf + conflictAt and shows both sides via markers (§7).
        val forkDoc = Frontmatter.parse(forkNote.wholeFileText)
        assertEquals(ID_A, forkDoc.conflictOf)
        assertEquals(CLOCK.toString(), forkDoc.conflictAt)
        val forkId = assertIs<String>(forkDoc.id)
        assertTrue(forkNote.wholeFileText.contains("<<<<<<<"))
        assertTrue(forkNote.wholeFileText.contains("beta one"))
        assertTrue(forkNote.wholeFileText.contains("======="))
        assertTrue(forkNote.wholeFileText.contains("beta two"))
        assertTrue(forkNote.wholeFileText.contains(">>>>>>>"))

        // Synology-convention filename is the REMOTE one (D8); the mirror keeps its
        // own `<ulid>-<slug>.md` path (§8) — identity lives in frontmatter (D3).
        val expectedForkName = "alpha_${DEVICE}_Aug-23-1004-2026_EditConflict_1.md"
        assertTrue(local.paths().any { it.endsWith("-alpha.md") })

        // The ORIGINAL remote note is untouched; the fork was uploaded create-only.
        assertEquals(originalRemoteText, remote.text(pathA))
        assertNotNull(remote.text(expectedForkName))
        assertEquals(forkNote.wholeFileText, remote.text(expectedForkName))

        // Both texts are recoverable: original's side lives locally, remote's side
        // lives remotely AND inside the fork's markers.
        assertTrue(remote.text(pathA)!!.contains("beta two"))

        // Termination ruling: base(original) advanced to the fork-carried side so the
        // next sync converges the original via an If-Match-guarded push, not a re-fork.
        assertEquals(originalRemoteText, book.baseOf(ID_A)?.body)
        assertEquals(forkNote.wholeFileText, book.baseOf(forkId)?.body)
    }

    @Test
    fun stale_etag_replans_then_merges() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        local.add(ID_A, pathA, note(ID_A, "Alpha", "alpha local\nbeta\ngamma\n"))
        remote.seed(pathA, note(ID_A, "Alpha", "alpha\nbeta\ngamma remote\n"), "\"r1\"")
        book.recordBase(ID_A, note(ID_A, "Alpha", "alpha\nbeta\ngamma\n"), "\"r1\"")
        remote.failNextPut412 += pathA

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 1,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            outcome,
        )
        // Initial planning GET plus EXACTLY ONE re-fetch during row-12 replan.
        assertEquals(2, remote.getCallCount(pathA))
        val pushedText = remote.text(pathA)!!
        assertTrue(pushedText.contains("alpha local"))
        assertTrue(pushedText.contains("gamma remote"))

        // First attempt raced (412); the replanned attempt landed against the fresh state.
        val attempts = remote.puts.filter { it.path == pathA }
        assertEquals(PutResult.PRECONDITION_FAILED, attempts[0].result)
        assertIs<PutResult.WRITTEN>(attempts[1].result)
    }

    @Test
    fun persistent_412_forks_after_three_rounds() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        val originalRemoteText = note(ID_A, "Alpha", "alpha\nbeta\ngamma remote\n")
        local.add(ID_A, pathA, note(ID_A, "Alpha", "alpha local\nbeta\ngamma\n"))
        remote.seed(pathA, originalRemoteText, "\"r1\"")
        book.recordBase(ID_A, note(ID_A, "Alpha", "alpha\nbeta\ngamma\n"), "\"r1\"")
        remote.putMode = InMemoryRemote.PutMode.ALWAYS_412

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 1, trashed = 0, resurrected = 0, nothing = 0,
            ),
            outcome,
        )
        // Bounded: three rounds, then fork — never a fourth attempt.
        assertEquals(3, remote.puts.count { it.path == pathA })
        assertTrue(
            remote.puts.none { it.path == pathA && it.result is PutResult.WRITTEN },
            "an overwrite got through a permanent 412",
        )
        assertEquals(originalRemoteText, remote.text(pathA))

        // The merge itself was CLEAN — the push merely raced — so there are no diff3
        // markers to show. Side-carry ruling: the fork carries the far side verbatim,
        // re-stamped with a fresh identity (§7).
        val forkNote = local.listLive().first { it.id != ID_A }
        val forkDoc = Frontmatter.parse(forkNote.wholeFileText)
        assertEquals(ID_A, forkDoc.conflictOf)
        assertEquals(
            Frontmatter.parse(originalRemoteText).bodyText,
            forkDoc.bodyText,
        )
        // Termination ruling: the original's base advanced to the remote side, so the
        // next sync converges via an If-Match-guarded push instead of re-forking forever.
        assertEquals(originalRemoteText, book.baseOf(ID_A)?.body)
    }

    @Test
    fun push_records_response_etag() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        val pushedText = note(ID_A, "Alpha", "alpha\nbeta\ngamma\n")
        local.add(ID_A, pathA, pushedText)
        // The server echoes this ETag response header for the new version (§4.2).
        remote.scriptPutEtag(pathA, "abc123")

        val first = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 1, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            first,
        )
        // The recorded base carries the RESPONSE etag, not null — the next push
        // can speak If-Match against the version the server actually created.
        assertEquals(pushedText, book.baseOf(ID_A)?.body)
        assertEquals("abc123", book.baseOf(ID_A)?.etag)

        // Conflicting remote write races ahead: new bytes, new server etag. The
        // stale recorded etag now makes every conditional push fail 412 for real.
        val conflictingRemote = note(ID_A, "Alpha", "alpha\nbeta\ngamma remote\n")
        remote.seed(pathA, conflictingRemote, "\"other\"")
        local.add(ID_A, pathA, note(ID_A, "Alpha", "alpha local\nbeta\ngamma\n"))

        val second = newEngine(local, remote, book).syncOnce()

        // Row-12 replans run their bounded course and fork — never an unconditional
        // overwrite of the raced write.
        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 1, trashed = 0, resurrected = 0, nothing = 0,
            ),
            second,
        )
        assertEquals(conflictingRemote, remote.text(pathA), "raced remote bytes were overwritten blind")
        val attempts = remote.puts.filter { it.path == pathA }
        assertEquals(4, attempts.size) // row-2 create + exactly three bounded merge rounds
        assertEquals(1, attempts.count { it.result is PutResult.WRITTEN })
        assertTrue(attempts.drop(1).all { it.result == PutResult.PRECONDITION_FAILED })

        // Termination ruling: the original's base advanced to the fork-carried side,
        // carrying that side's listing etag; the fork itself was uploaded create-only.
        assertEquals(conflictingRemote, book.baseOf(ID_A)?.body)
        assertEquals("\"other\"", book.baseOf(ID_A)?.etag)
        val forkNote = local.listLive().first { it.id != ID_A }
        assertEquals(ID_A, Frontmatter.parse(forkNote.wholeFileText).conflictOf)
    }

    @Test
    fun row9_remote_gone_trashes_local_copy() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        // Four agreed notes keep the pass at exactly the 25% boundary (§9 halts only
        // ABOVE the threshold), then delta vanishes from the far side.
        seedAgreed(ID_A, "Alpha", "alpha\n", local, remote, book)
        seedAgreed(ID_B, "Bravo", "bravo\n", local, remote, book)
        seedAgreed(ID_C, "Charlie", "charlie\n", local, remote, book)
        val deltaBytes = note(ID_D, "Delta", "delta\n")
        val pathD = "$ID_D-delta.md"
        local.add(ID_D, pathD, deltaBytes)
        book.recordBase(ID_D, deltaBytes, "\"gone\"")

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 0, trashed = 1, resurrected = 0, nothing = 3,
            ),
            outcome,
        )
        assertTrue(local.listLive().none { it.id == ID_D })
        val trashed = local.listTrashed().first { it.id == ID_D }
        // Bytes retained — trash never unlinks (D9).
        assertEquals(deltaBytes, trashed.wholeFileText)
        // Base kept deliberately: trash is reversible (§6 row 7/9 note).
        assertNotNull(book.baseOf(ID_D))
    }

    @Test
    fun row10_local_edit_outranks_remote_delete() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathD = "$ID_D-delta.md"
        val editedLocal = note(ID_D, "Delta", "delta edited on the phone\n")
        local.add(ID_D, pathD, editedLocal)
        book.recordBase(ID_D, note(ID_D, "Delta", "delta\n"), "\"gone\"")

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 0, trashed = 0, resurrected = 1, nothing = 0,
            ),
            outcome,
        )
        // The edit wins: recreated create-only on the far side (D10).
        assertEquals(editedLocal, remote.text(pathD))
        assertTrue(local.listLive().any { it.id == ID_D })
        assertEquals(editedLocal, book.baseOf(ID_D)?.body)
    }

    @Test
    fun row8_resurrect_keeps_trashed_copy_as_conflict_fork() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        val trashedBytes = note(ID_A, "Alpha", "stale trashed copy\n")
        local.add(ID_A, pathA, trashedBytes, trashed = true)
        val editedRemote = note(ID_A, "Alpha", "edited elsewhere while trashed here\n")
        remote.seed(pathA, editedRemote, "\"r2\"")
        book.recordBase(ID_A, note(ID_A, "Alpha", "original\n"), "\"r1\"")

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 1, trashed = 0, resurrected = 1, nothing = 0,
            ),
            outcome,
        )
        // Remote wins live: restored under the original id carrying the remote bytes (§6 row 8).
        val live = local.listLive().first { it.id == ID_A }
        assertEquals(editedRemote, live.wholeFileText)
        assertEquals(listOf(ID_A), local.listLive().map { it.id })

        // The deleting side's copy SURVIVES in trash under its own fresh identity,
        // stamped conflictOf back to the original id — the restore must never
        // destroy it (§15: never lose text).
        val trashFork = local.listTrashed().single()
        val forkDoc = Frontmatter.parse(trashFork.wholeFileText)
        val forkId = assertNotNull(forkDoc.id)
        assertTrue(Ulid.isValid(forkId), "trashed copy needs a fresh ULID of its own")
        assertTrue(forkId != ID_A)
        assertEquals(ID_A, forkDoc.conflictOf)
        assertEquals(CLOCK.toString(), forkDoc.conflictAt)
        // Its bytes are exactly the old trashed text, re-stamped — both sides'
        // texts exist after the pass.
        assertEquals("stale trashed copy\n", forkDoc.bodyText)

        // The restored note's base carries what was pulled.
        assertEquals(editedRemote, book.baseOf(ID_A)?.body)
    }

    @Test
    fun auth_refusal_halts_pass_as_authFailed_without_enqueuing_ops() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        seedAgreed(ID_A, "Alpha", "alpha\n", local, remote, book)
        local.writeCalls.clear()
        remote.authStatus = 401

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(SyncEngine.SyncOutcome.AuthFailed(401), outcome)
        // Nothing applied anywhere and NOTHING queued for a retry loop to chew on.
        assertTrue(remote.puts.isEmpty())
        assertTrue(local.writeCalls.isEmpty())
        assertTrue(book.pendingOps().isEmpty())
    }

    @Test
    fun auth_forbidden_403_reports_its_status() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        seedAgreed(ID_A, "Alpha", "alpha\n", local, remote, book)
        remote.authStatus = 403

        assertEquals(SyncEngine.SyncOutcome.AuthFailed(403), newEngine(local, remote, book).syncOnce())
    }

    @Test
    fun row7_trash_move_never_clobbers_a_copy_already_in_trash() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        val text = note(ID_A, "Alpha", "alpha\n")
        local.add(ID_A, pathA, text, trashed = true)
        remote.seed(pathA, text, "\"r1\"")
        book.recordBase(ID_A, text, "\"r1\"")
        // An earlier trash of the same-named file already rests on the far side.
        remote.seed(".xxnote/trash/$ID_A-alpha.md", "earlier copy\n", "\"t1\"")

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 0, trashed = 1, resurrected = 0, nothing = 0,
            ),
            outcome,
        )
        // First attempt refused (Overwrite: F against an occupied destination),
        // retried with the `_1` suffix on the stem — the earlier copy is NOT clobbered.
        assertEquals(
            listOf(
                pathA to ".xxnote/trash/$ID_A-alpha.md",
                pathA to ".xxnote/trash/$ID_A-alpha_1.md",
            ),
            remote.moves,
        )
        assertEquals("earlier copy\n", remote.text(".xxnote/trash/$ID_A-alpha.md"))
        assertEquals(text, remote.text(".xxnote/trash/$ID_A-alpha_1.md"))
        // The skeleton was ensured once per pass before any move.
        assertTrue(remote.mkcols.isNotEmpty())
    }

    @Test
    fun vault_safety_halts_over_threshold() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val paths = listOf(
            seedAgreed(ID_A, "Alpha", "alpha\n", local, remote, book),
            seedAgreed(ID_B, "Bravo", "bravo\n", local, remote, book),
            seedAgreed(ID_C, "Charlie", "charlie\n", local, remote, book),
            seedAgreed(ID_D, "Delta", "delta\n", local, remote, book),
        )
        // Three of four notes vanished on the far side — the empty-share shape §9 exists for.
        listOf(paths[1], paths[2], paths[3]).forEach { remote.delete(it) }
        val remoteBefore = remote.snapshot()
        val basesBefore = book.bases.toMap()

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(SyncEngine.SyncOutcome.HaltedTrashSafety(wouldTrash = 3, liveNotes = 4), outcome)
        // NOTHING applied anywhere: remote untouched by this pass...
        assertEquals(remoteBefore, remote.snapshot())
        assertTrue(remote.puts.isEmpty())
        assertTrue(remote.moves.isEmpty())
        // ...the mirror kept all four notes live...
        assertTrue(local.trashCalls.isEmpty())
        assertEquals(4, local.listLive().size)
        // ...and every base survived byte-for-byte.
        assertEquals(basesBefore, book.bases)
        assertTrue(book.logs.any { it.verdict == "HaltedTrashSafety" && !it.ok })
    }

    @Test
    fun offline_push_enqueues_outbox_op_then_next_sync_applies() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val pathA = "$ID_A-alpha.md"
        val localEdit = note(ID_A, "Alpha", "written on the train\n")
        local.add(ID_A, pathA, localEdit)
        remote.seed(pathA, note(ID_A, "Alpha", "last synced\n"), "\"r1\"")
        book.recordBase(ID_A, note(ID_A, "Alpha", "last synced\n"), "\"r1\"")
        remote.putMode = InMemoryRemote.PutMode.ALWAYS_FAIL

        val first = newEngine(local, remote, book).syncOnce()
        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            first,
        )
        // The failed push queued exactly one idempotent 'put' op.
        val queued = book.pendingOps()
        assertEquals(1, queued.size)
        assertEquals(SyncEngine.OP_PUT, queued[0].op)
        assertEquals(ID_A, queued[0].noteId)
        assertEquals(localEdit, queued[0].payload)

        // Tailnet back: the note simply re-enters §6 — no blind replay needed —
        // lands its push, and the now-satisfied op drains.
        remote.putMode = InMemoryRemote.PutMode.NORMAL
        val second = newEngine(local, remote, book).syncOnce()
        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 1, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            second,
        )
        assertEquals(localEdit, remote.text(pathA))
        assertEquals(localEdit, book.baseOf(ID_A)?.body)
        assertTrue(book.pendingOps().isEmpty())
        assertTrue(book.logs.any { it.noteId == ID_A && it.reason.contains("drained") })
    }

    // ---- D9 expiry sweep (H4) -------------------------------------------------------

    @Test
    fun expiry_sweep_purges_expired_trash_on_both_sides_and_spares_unstamped() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()

        // Remote trash, 7d00h01m old → past retention, deleted.
        val expiredRemotePath = ".xxnote/trash/$ID_B-bravo.md"
        remote.seed(
            expiredRemotePath,
            trashedNote(ID_B, "Bravo", "expired bytes\n", stamp = "2026-08-16T10:03:00Z"),
            "\"t1\"",
        )
        // Remote trash WITHOUT a trashedAt stamp → NEVER deleted (D9 belt-and-braces).
        val unstampedPath = ".xxnote/trash/unstamped.md"
        remote.seed(unstampedPath, note("no-id-claim", "Unstamped", "keep me\n"), "\"t2\"")
        // Local trash, weeks old → purged by the local half.
        val expiredLocalId = ID_C
        local.add(
            expiredLocalId, ".xxnote/trash/charlie.md",
            trashedNote(ID_C, "Charlie", "old trash\n", stamp = "2026-08-01T00:00:00Z"),
            trashed = true,
        )

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 0, trashed = 0, resurrected = 0,
                nothing = 1, // the baseless trashed local orphan re-decides to Nothing
                expired = 2, // one copy from each side
            ),
            outcome,
        )
        assertNull(remote.text(expiredRemotePath))
        assertTrue(local.listTrashed().none { it.id == expiredLocalId })

        // The unstamped copy survived AND was flagged, never silently kept or deleted.
        assertEquals(note("no-id-claim", "Unstamped", "keep me\n"), remote.text(unstampedPath))
        assertTrue(
            book.logs.any { it.verdict == "Expire" && !it.ok && it.reason.contains("trashedAt") },
        )
        assertTrue(book.logs.any { it.verdict == "Expire" && it.ok && it.reason.contains(expiredRemotePath) })
    }

    @Test
    fun expiry_sweep_spares_everything_inside_the_retention_window() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()

        // 6d23h old — one hour inside the window — survives on both sides.
        val survivorStamp = "2026-08-16T11:04:00Z"
        val survivorRemotePath = ".xxnote/trash/$ID_A-alpha.md"
        remote.seed(
            survivorRemotePath,
            trashedNote(ID_A, "Alpha", "still resting\n", stamp = survivorStamp),
            "\"t1\"",
        )
        local.add(
            ID_D, ".xxnote/trash/delta.md",
            trashedNote(ID_D, "Delta", "also resting\n", stamp = survivorStamp),
            trashed = true,
        )

        val outcome = newEngine(local, remote, book).syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 0, trashed = 0, resurrected = 0,
                nothing = 1,
                expired = 0,
            ),
            outcome,
        )
        assertEquals(
            trashedNote(ID_A, "Alpha", "still resting\n", stamp = survivorStamp),
            remote.text(survivorRemotePath),
        )
        assertTrue(local.listTrashed().any { it.id == ID_D })
    }

    @Test
    fun r3_analog_clear_cache_resync_loses_nothing() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()

        // Phase 1 — three local-only notes get pushed up (row 2, create-only).
        val texts = mapOf(
            ID_A to note(ID_A, "Alpha", "groceries: oat milk, coffee\n"),
            ID_B to note(ID_B, "Bravo", "standup notes\nsecond line\n"),
            ID_C to note(ID_C, "Charlie", "- [ ] bin bags\n- [x] done thing\n"),
        )
        texts.forEach { (id, text) -> local.add(id, "$id-x.md", text) }

        val firstPass = newEngine(local, remote, book).syncOnce()
        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 3, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            firstPass,
        )
        val preWipe = texts.values.associateBy { Frontmatter.parse(it).id!! }

        // Phase 2 — clear app data: the mirror empties, bases are forgotten.
        // The remote vault (truth, D1/R3) is left exactly as synced.
        local.clear()
        book.forgetAllBases()

        // Phase 3 — resync pulls everything back byte-identical (row 1).
        val third = newEngine(local, remote, book).syncOnce()
        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 3, pushed = 0, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            third,
        )
        val recovered = local.listLive().associateBy { it.id }
        for ((id, expected) in preWipe) {
            val noteNow = recovered[id]
            assertNotNull(noteNow, "note $id lost across wipe+resync")
            assertEquals(expected, noteNow.wholeFileText, "bytes differ for $id after resync")
            assertNotNull(book.baseOf(id), "base not rebuilt for $id")
        }
    }
}
