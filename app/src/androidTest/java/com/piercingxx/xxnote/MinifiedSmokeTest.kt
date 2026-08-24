package com.piercingxx.xxnote

import android.content.Context
import android.security.NetworkSecurityPolicy
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.piercingxx.xxnote.data.NoteEntity
import com.piercingxx.xxnote.data.XxDatabase
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.runner.RunWith

/**
 * Hardening #14b / #10: a minified release build must still open Room, hold
 * its transport rules, and run WorkManager's reflective worker instantiation.
 *
 * Authored and compiled without a device attached; execution is pending
 * hardware — against the minified variant specifically:
 * `connectedReleaseAndroidTest`. (Names are dex-safe camelCase, not the JVM
 * suite's backtick style.)
 */
@RunWith(AndroidJUnit4::class)
class MinifiedSmokeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private var db: XxDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(DB_NAME)
        WorkManager.getInstance(context).cancelAllWork()
    }

    /**
     * Opens the full schema — ten entities plus the FTS4 external-content
     * table and its triggers — round-trips one row through the generated DAO,
     * and answers an FTS MATCH query. Deliberately NOT XxDatabase.NAME: the
     * device under test may hold real vault state, and this test must stay
     * inert toward it (D1: vault is truth, Room is cache).
     */
    @Test
    fun roomOpensTheSchemaRoundTripsARowAndAnswersFts() = runBlocking {
        val database = Room.databaseBuilder(context, XxDatabase::class.java, DB_NAME).build()
        db = database

        val dao = database.noteDao()
        val note = NoteEntity(
            id = "smoke-1",
            path = "notes/smoke-1.md",
            title = "kepler",
            body = "harmonices mundi",
            created = 0L,
            updated = 0L,
            pinned = false,
            archived = false,
            color = null,
            type = "note",
            trashedAt = null,
            conflictOf = null,
            extraFrontmatter = null,
        )

        dao.upsert(note)

        assertEquals(note, dao.byId("smoke-1"))
        assertEquals(listOf("smoke-1"), dao.search("mundi").map { it.id })

        dao.delete("smoke-1")
        assertEquals(null, dao.byId("smoke-1"))
    }

    /**
     * The transport check stops at client construction because the app's
     * network security config forbids cleartext everywhere — loopback
     * included — so no plain-HTTP server can answer inside this process, and
     * widening the config is exactly what that file's contract forbids. A
     * live round-trip happens over HTTPS against the real DSM (WS4 gate) or
     * in the JVM suite via MockWebServer. What is proven here: the same
     * builder shape WebDavClient uses constructs on a minified build, and the
     * shipped cleartext ban is active in the APK under test.
     */
    @Test
    fun okhttpBuildsAndTheOneHostTransportRulesHold() {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        assertFalse(client.followRedirects)
        assertFalse(
            NetworkSecurityPolicy.getInstance()
                .isCleartextTrafficPermitted("127.0.0.1"),
        )
    }

    /** Reflectively instantiated by WorkerFactory — the R8-sensitive surface. */
    class ProbeWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result = Result.success()
    }

    @Test
    fun workManagerEnqueuesAndCompletesOneProbeWork() = runBlocking {
        val workManager = WorkManager.getInstance(context)
        val probe = OneTimeWorkRequestBuilder<ProbeWorker>().build()
        workManager.enqueue(probe)

        val info = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(probe.id)
                .first { it != null && it.state.isFinished }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, info?.state)
    }

    private companion object {
        const val DB_NAME = "xx-note-smoke.db"
        const val TIMEOUT_MS = 30_000L
    }
}
