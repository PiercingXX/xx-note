package com.piercingxx.xxnote.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncLogDaoTest {

    private lateinit var db: XxDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            XxDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    private suspend fun insert(at: Long, verdict: String = "Nothing") {
        db.syncLogDao().insert(
            SyncLogEntity(
                at = at,
                noteId = "n-$at",
                verdict = verdict,
                reason = "row $at",
                ok = true,
                detail = null,
            ),
        )
    }

    @Test
    fun pruneKeepsNewestThousandOldestFirstOut() = runBlocking {
        for (i in 1..SyncLogDao.LOG_CAP + 5) insert(at = i.toLong())
        db.syncLogDao().pruneToCap()

        val rows = db.syncLogDao().latest(SyncLogDao.LOG_CAP + 10)
        assertEquals(SyncLogDao.LOG_CAP, rows.size)
        // Autoincrement ids correlate with insertion order; the five oldest are gone.
        assertEquals((6L..1005L).toList().reversed(), rows.map { it.at })
    }

    @Test
    fun latestReturnsMostRecentFirst() = runBlocking {
        for (i in 1..5L) insert(at = i * 100)
        val latest = db.syncLogDao().latest(3)
        assertEquals(listOf(500L, 400L, 300L), latest.map { it.at })
        assertTrue(latest.all { it.reason.startsWith("row ") })
    }
}
