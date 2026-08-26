package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §12's disclosed import (bug 5): a folder of id-less Markdown becomes a
 * vault of identified notes — stamped on the SERVER under `If-Match`, never
 * overwriting, idempotent on the second run — driven against the same pure
 * fakes the engine tests use.
 */
class ImportPassTest {

    private companion object {
        val CLOCK: Instant = Instant.parse("2026-08-23T10:04:00Z")
        const val ID_A = "01J9F2K3M4N5P6Q7R8S9T0V1W2"

        /** An Obsidian-shaped file: no id, unknown keys an import must keep. */
        const val ID_LESS =
            "---\ntitle: Grocery\nobsidian_thing: keep me\ncssclasses: [x]\n---\nbody one\n"
    }

    private fun import(remote: InMemoryRemote): ImportPass.Report =
        ImportPass(remote, clock = { CLOCK }).run()

    @Test
    fun id_less_file_gains_a_canonical_ulid_and_unknown_keys_survive_byte_exact() {
        val remote = InMemoryRemote()
        remote.seed("grocery.md", ID_LESS, "\"e1\"")

        val report = import(remote)

        assertEquals(1, report.seen)
        assertEquals(1, report.stamped)
        assertEquals(0, report.alreadyIdentified)
        val out = remote.text("grocery.md")!!
        val doc = Frontmatter.parse(out)
        val id = doc.id
        assertTrue(id != null && Ulid.isValid(id), "not a canonical ULID: $id")
        assertEquals(CLOCK.toEpochMilli(), Ulid.timestampOf(id))
        // Unknown keys verbatim — position, spelling, quoting untouched.
        assertTrue(out.contains("\nobsidian_thing: keep me\n"))
        assertTrue(out.contains("\ncssclasses: [x]\n"))
        assertEquals("Grocery", doc.title)
        assertEquals("body one\n", doc.bodyText)
        // Conditional write against the etag observed at listing time.
        val put = remote.puts.single()
        assertEquals("grocery.md", put.path)
        assertEquals("\"e1\"", put.ifMatch)
    }

    @Test
    fun malformed_frontmatter_degrades_to_body_and_gains_a_fresh_block() {
        val remote = InMemoryRemote()
        remote.seed(
            "broken.md",
            "---\nthis line has no colon shape\nand no close\nbody survives\n",
            "\"e2\"",
        )

        val report = import(remote)

        assertEquals(1, report.stamped)
        val out = remote.text("broken.md")!!
        val doc = Frontmatter.parse(out)
        assertTrue(doc.hasFrontmatter, "the stamp must prepend a well-formed block")
        assertTrue(Ulid.isValid(doc.id!!))
        // Nothing was discarded: the old broken block rides in the body (R5).
        assertTrue(doc.bodyText.contains("---\nthis line has no colon shape\nand no close\nbody survives\n"))
    }

    @Test
    fun second_import_pass_is_a_no_op() {
        val remote = InMemoryRemote()
        remote.seed("a.md", ID_LESS, "\"e1\"")
        remote.seed("b.md", "---\ntitle: B\n---\nbody two\n", "\"e2\"")

        val first = import(remote)
        assertEquals(2, first.stamped)
        val afterFirst = remote.snapshot()

        val second = import(remote)

        assertEquals(2, second.alreadyIdentified)
        assertEquals(0, second.stamped)
        assertEquals(0, remote.puts.size - 2, "the second pass wrote again")
        assertEquals(afterFirst, remote.snapshot())
        // Stable identities: each file keeps exactly the id the first pass gave.
        assertNotEquals(
            Frontmatter.parse(remote.text("a.md")!!).id,
            Frontmatter.parse(remote.text("b.md")!!).id,
        )
    }

    @Test
    fun files_that_already_carry_valid_ulids_are_never_rewritten() {
        val remote = InMemoryRemote()
        val identified = "---\nid: $ID_A\ntitle: Alpha\n---\nbody\n"
        remote.seed("alpha.md", identified, "\"e1\"")

        val report = import(remote)

        assertEquals(1, report.alreadyIdentified)
        assertEquals(0, report.stamped)
        assertTrue(remote.requests.isEmpty())
        assertEquals(identified, remote.text("alpha.md"))
    }

    @Test
    fun concurrent_change_during_stamping_leaves_the_file_untouched_and_flagged() {
        val remote = InMemoryRemote()
        remote.seed("grocery.md", ID_LESS, "\"e1\"")
        remote.failNextPut412 += "grocery.md" // someone else wrote since we listed

        val report = import(remote)

        assertEquals(1, report.raced)
        assertEquals(0, report.stamped)
        assertEquals(ID_LESS, remote.text("grocery.md"), "a raced import overwrote")
        assertTrue(
            report.plainWords().any { it.contains("left untouched for the next sync") },
            "plain words must flag the raced file",
        )
    }

    @Test
    fun missing_or_weak_etag_is_refused_without_any_request() {
        val remote = InMemoryRemote()
        // Empty string models "no usable ETag came back with the listing".
        remote.seed("no-lock.md", ID_LESS, "")
        remote.seed("weak.md", ID_LESS, "W/\"w\"")

        val report = import(remote)

        assertEquals(2, report.refusedNoLock)
        assertEquals(0, report.stamped)
        assertTrue(remote.requests.isEmpty(), "a lock-less import PUT escaped")
        assertEquals(ID_LESS, remote.text("no-lock.md"))
        assertEquals(ID_LESS, remote.text("weak.md"))
        assertTrue(
            report.plainWords().any { it.contains("no lockable ETag") },
            "plain words must flag the refusal",
        )
    }

    @Test
    fun imported_files_land_in_the_vault_on_the_following_sync_and_resync_is_quiet() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        remote.seed("a.md", ID_LESS, "\"e1\"")
        remote.seed("b.md", "---\ntitle: B\nobsidian_thing: keep\n---\nbody two\n", "\"e2\"")

        // Setup's shape: import pass, then the ordinary first sync pulls.
        ImportPass(remote, clock = { CLOCK }).run()
        val outcome = SyncEngine(local, remote, book, "device", clock = { CLOCK }).syncOnce()
        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 2, pushed = 0, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            outcome,
        )
        val vault = local.listLive().associateBy { Frontmatter.parse(it.wholeFileText).title }
        assertEquals(setOf("Grocery", "B"), vault.keys)
        for ((_, note) in vault) {
            assertTrue(Ulid.isValid(Frontmatter.parse(note.wholeFileText).id!!))
        }
        assertEquals("body one\n", Frontmatter.parse(vault.getValue("Grocery").wholeFileText).bodyText)
        assertEquals(
            "keep", Frontmatter.parse(vault.getValue("B").wholeFileText).let { it.raw().substringAfter("obsidian_thing: ").substringBefore('\n') },
        )

        // The second sync is the idempotence proof: nothing re-stamped, no
        // duplicate set, every note already agreed on both sides.
        val secondImport = ImportPass(remote, clock = { CLOCK }).run()
        assertEquals(2, secondImport.alreadyIdentified)
        assertEquals(0, secondImport.stamped)
        val putsBefore = remote.puts.size
        val secondSync = SyncEngine(local, remote, book, "device", clock = { CLOCK }).syncOnce()
        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 0, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 2,
            ),
            secondSync,
        )
        assertEquals(putsBefore, remote.puts.size)
        assertEquals(2, local.listLive().size, "a duplicate note appeared")
    }

    @Test
    fun listing_failure_is_reported_in_plain_words_not_as_an_empty_folder() {
        val remote = InMemoryRemote()
        remote.seed("a.md", ID_LESS, "\"e1\"")
        remote.reachable = false

        val report = import(remote)

        assertTrue(report.listingFailed)
        assertEquals(0, report.seen)
        assertEquals(0, report.stamped)
        assertTrue(
            report.plainWords().any { it.contains("could not list the folder to import") },
            "a failed listing must speak, not masquerade as nothing to import",
        )
        assertEquals(ID_LESS, remote.text("a.md"), "a failed listing must not touch the file")
    }
}
