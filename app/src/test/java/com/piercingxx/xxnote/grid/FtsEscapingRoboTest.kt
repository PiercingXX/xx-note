package com.piercingxx.xxnote.grid

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.data.NoteEntity
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.ui.grid.SearchQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WS8 gate, end-to-end over REAL SQLite FTS4 (Robolectric + in-memory Room):
 * every sanitized expression here is one a user could plausibly type into
 * the top-bar search field. The pure [SearchQueryTest] pins the emitted
 * bytes; this suite proves those bytes PARSE and MATCH correctly against
 * `note_fts` — no `malformed MATCH expression`, no injection of query
 * syntax, prefix matching where promised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FtsEscapingRoboTest {

    private lateinit var db: XxDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            XxDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    private fun note(
        id: String,
        title: String,
        body: String,
        trashedAt: Long? = null,
    ) = NoteEntity(
        id = id,
        path = if (trashedAt == null) "$id-slug.md" else ".xxnote/trash/$id-slug.md",
        title = title,
        body = "---\nid: $id\ntitle: $title\n---\n\n$body",
        created = 1_780_000_000_000,
        updated = 1_780_000_000_000,
        pinned = false,
        archived = false,
        color = null,
        type = "note",
        trashedAt = trashedAt,
        conflictOf = null,
        extraFrontmatter = null,
    )

    private suspend fun hits(rawOrSanitized: String): List<String> =
        db.noteDao().search(rawOrSanitized).map { it.id }

    private suspend fun hitsFor(raw: String): List<String> =
        db.noteDao().search(SearchQuery.sanitize(raw)).map { it.id }

    @Test
    fun plainPrefixQueryFindsTitleAndBody() = runBlocking {
        val oat = "01J9F2K3M4N5P6Q7R8S9T0V1W2"
        val budget = "01J9F2K8ZZ1A2B3C4D5E6F7G8H"
        db.noteDao().upsert(note(oat, "Shopping", "oat milk and bin bags"))
        db.noteDao().upsert(note(budget, "Work", "quarterly budget review"))

        assertEquals(listOf(oat), hitsFor("oat"))
        assertTrue(hitsFor("budget").contains(budget))
        // Prefix behaviour: "quar" reaches "quarterly".
        assertTrue(hitsFor("quar").contains(budget))
        assertTrue(hitsFor("zebra").isEmpty())
    }

    @Test
    fun unbalancedQuoteNeverThrows_andStillFindsTheWord() = runBlocking {
        val hello = "01J9F2KA0BCDEFGHJKMNPQRSTVW"
        db.noteDao().upsert(note(hello, "Greeting", "say \"hello world"))

        // Raw, this MATCH expression is malformed; sanitized it must parse.
        val ids = hitsFor("say \"hello")
        assertTrue(ids.contains(hello))
    }

    @Test
    fun punctuationOnlyQueryReturnsEmptyWithoutError() = runBlocking {
        db.noteDao().upsert(note("01J9F2K3M4N5P6Q7R8S9T0V1W2", "T", "some words"))
        assertTrue(hitsFor("*** --- !!!").isEmpty())
        assertTrue(hitsFor("\"").isEmpty())
    }

    @Test
    fun leadingHyphenIsNeutralized_notANotOperator() = runBlocking {
        val work = "01J9F2K8ZZ1A2B3C4D5E6F7G8H"
        val other = "01J9F2K3M4N5P6Q7R8S9T0V1W2"
        db.noteDao().upsert(note(work, "Work", "quarterly review notes"))
        db.noteDao().upsert(note(other, "Life", "unrelated entirely"))

        // "-quarterly" must SEARCH for quarterly, not exclude it.
        assertTrue(hitsFor("-quarterly").contains(work))
    }

    @Test
    fun uppercaseOperatorsDoNotParseAsOperators() = runBlocking {
        val doc = "01J9F2K3M4N5P6Q7R8S9T0V1W2"
        db.noteDao().upsert(note(doc, "Groceries", "milk oat and android bread"))

        // Bare `milk AND` is malformed FTS4; sanitized, "AND" becomes an inert
        // searchable word and the query still finds the note.
        assertTrue(hitsFor("milk AND").contains(doc))
        assertTrue(db.noteDao().search(SearchQuery.sanitize("NOT")).isEmpty())
    }

    @Test
    fun cjkPrefixReachesInsideASimpleTokenizerRun() = runBlocking {
        val cjk = "01J9F2KA0BCDEFGHJKMNPQRSTVW"
        db.noteDao().upsert(note(cjk, "中文", "中国菜很好吃 very tasty"))

        assertTrue(hitsFor("中国菜很").contains(cjk))   // partial run, prefix
        assertTrue(hitsFor("中国菜很好吃").contains(cjk)) // full run
    }

    @Test
    fun trashedNotesNeverSurfaceEvenWhenTextMatches() = runBlocking {
        db.noteDao().upsert(note("01J9F2K3M4N5P6Q7R8S9T0V1W2", "Visible", "public oat stash"))
        db.noteDao().upsert(
            note("01J9F2K8ZZ1A2B3C4D5E6F7G8H", "Hidden", "secret oat stash", trashedAt = 99L),
        )
        assertEquals(
            listOf("01J9F2K3M4N5P6Q7R8S9T0V1W2"),
            hitsFor("stash"),
        )
    }
}
