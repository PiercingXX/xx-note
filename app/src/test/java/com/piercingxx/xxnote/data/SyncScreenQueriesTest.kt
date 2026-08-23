package com.piercingxx.xxnote.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The read-only queries WS9's screen leans on, modeled against fixtures:
 * conflict-pair detection (§7 — live originals having a live fork) and the
 * tallies' trailing window over sync_log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncScreenQueriesTest {

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
        title: String = id,
        updated: Long = 0L,
        trashedAt: Long? = null,
        conflictOf: String? = null,
    ) = NoteEntity(
        id = id,
        path = "$id.md",
        title = title,
        body = "---\nid: $id\ntitle: $title\n---\n\nbody",
        created = 0L,
        updated = updated,
        pinned = false,
        archived = false,
        color = null,
        type = "note",
        trashedAt = trashedAt,
        conflictOf = conflictOf,
        extraFrontmatter = null,
    )

    @Test
    fun conflictsList_liveOriginalWithLiveFork_isListed() = runBlocking {
        db.noteDao().upsert(note(id = "orig", title = "Grocery list", updated = 10))
        db.noteDao().upsert(note(id = "fork", title = "fork", updated = 11, conflictOf = "orig"))

        val rows = db.noteDao().conflictsList()
        assertEquals(listOf("orig"), rows.map { it.id })
    }

    @Test
    fun conflictsList_trashingTheFork_resolvesTheOriginal() = runBlocking {
        db.noteDao().upsert(note(id = "orig"))
        db.noteDao().upsert(note(id = "fork", conflictOf = "orig", trashedAt = 5))

        assertEquals(emptyList<String>(), db.noteDao().conflictsList().map { it.id })
    }

    @Test
    fun conflictsList_trashedOriginal_neverLists_evenWithLiveFork() = runBlocking {
        db.noteDao().upsert(note(id = "orig", trashedAt = 3))
        db.noteDao().upsert(note(id = "fork", conflictOf = "orig"))

        assertEquals(emptyList<String>(), db.noteDao().conflictsList().map { it.id })
    }

    @Test
    fun conflictsList_danglingConflictOf_listsNothing() = runBlocking {
        db.noteDao().upsert(note(id = "fork", conflictOf = "ghost"))
        assertEquals(emptyList<String>(), db.noteDao().conflictsList().map { it.id })
    }

    @Test
    fun conflictsList_multipleForks_listTheOriginalOnce_newestFirst() = runBlocking {
        db.noteDao().upsert(note(id = "a", updated = 1))
        db.noteDao().upsert(note(id = "b", updated = 99))
        db.noteDao().upsert(note(id = "f1", conflictOf = "a"))
        db.noteDao().upsert(note(id = "f2", conflictOf = "a"))
        db.noteDao().upsert(note(id = "g1", conflictOf = "b"))

        val ids = db.noteDao().conflictsList().map { it.id }
        assertEquals(listOf("b", "a"), ids)
    }

    private suspend fun logRow(at: Long) {
        db.syncLogDao().insert(
            SyncLogEntity(at = at, noteId = null, verdict = "Push", reason = "r", ok = true, detail = null),
        )
    }

    @Test
    fun logsSince_returnsOnlyEntriesAtOrAfterTheBoundary_newestFirst() = runBlocking {
        logRow(at = 100)
        logRow(at = 200)
        logRow(at = 300)

        assertEquals(listOf(300L, 200L), db.syncLogDao().logsSince(200).map { it.at })
        assertEquals(listOf(300L, 200L, 100L), db.syncLogDao().logsSince(0).map { it.at })
        assertEquals(emptyList<Long>(), db.syncLogDao().logsSince(301).map { it.at })
    }
}
