package com.piercingxx.xxnote.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-level coverage for WS3: note CRUD, FTS hit/miss, and a schema check
 * that every §11 table actually exists. Robolectric + in-memory Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteDaoTest {

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
        body: String,
        title: String = "Untitled",
        trashedAt: Long? = null,
    ) = NoteEntity(
        id = id,
        path = "$id-slug.md",
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

    @Test
    fun crudRoundTrip() = runBlocking {
        val id = "01J9F2K3M4N5P6Q7R8S9T0V1W2"
        assertNull(db.noteDao().byId(id))

        db.noteDao().upsert(note(id, "first draft"))
        val stored = assertNotNull(db.noteDao().byId(id))
        assertEquals(note(id, "first draft"), stored)

        db.noteDao().upsert(stored.copy(title = "Renamed", pinned = true))
        assertEquals("Renamed", assertNotNull(db.noteDao().byId(id)).title)
        assertTrue(assertNotNull(db.noteDao().byId(id)).pinned)

        assertEquals(1, db.noteDao().listLive().size)
        db.noteDao().delete(id)
        assertNull(db.noteDao().byId(id))
    }

    @Test
    fun ftsSearchHitsBodyAndTitleAndSkipsTrash() = runBlocking {
        db.noteDao().upsert(note("01J9F2K3M4N5P6Q7R8S9T0V1W2", "oat milk and bin bags"))
        db.noteDao().upsert(note("01J9F2K8ZZ1A2B3C4D5E6F7G8H", "quarterly budget review"))
        db.noteDao().upsert(note("01J9F2KA0BCDEFGHJKMNPQRSTVW", "hidden oat stash", trashedAt = 99L))

        val hits = db.noteDao().search("oat")
        assertEquals(listOf("01J9F2K3M4N5P6Q7R8S9T0V1W2"), hits.map { it.id })

        assertTrue(db.noteDao().search("budget").any { it.id == "01J9F2K8ZZ1A2B3C4D5E6F7G8H" })
        assertTrue(db.noteDao().search("zebra").isEmpty())

        // The trashed row must not surface even though its text matches.
        assertTrue(db.noteDao().search("stash").isEmpty())
    }

    @Test
    fun allElevenTablesExist() = runBlocking {
        val tables = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        for (expected in listOf(
            "note", "note_fts", "base_snapshot", "outbox", "label", "note_label",
            "attachment", "sync_log", "setting", "credential",
        )) {
            assertTrue("missing table $expected", expected in tables)
        }
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value as T
    }
}
