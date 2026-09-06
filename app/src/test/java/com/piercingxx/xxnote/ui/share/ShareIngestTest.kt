package com.piercingxx.xxnote.ui.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.data.AttachmentStore
import com.piercingxx.xxnote.data.FakeAttachmentDao
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.ui.setup.SetupLogic
import java.io.File
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
 * P2 ingest: a local-only (or credentialed) vault gets a dirty new `.md`;
 * Setup unfinished without the sentinel refuses to write. Text files become
 * vault attachments + a Markdown link; non-text is refused honestly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIngestTest {

    private lateinit var root: File
    private lateinit var db: XxDatabase
    private lateinit var store: VaultStore
    private lateinit var attachments: AttachmentStore
    private val attDao = FakeAttachmentDao()

    @Before
    fun setUp() {
        val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        root = File(filesDir, "vault-share-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            XxDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = VaultStore(root, db)
        attachments = AttachmentStore(root, attDao)
    }

    @Test
    fun gateRefusesWhenSetupIsUnfinishedAndThereIsNoLocalOnlySentinel() {
        assertEquals(
            ShareIngest.Outcome.NeedSetup,
            ShareIngest.gate(hasCredential = false, localOnly = false),
        )
        assertNull(ShareIngest.gate(hasCredential = false, localOnly = true))
        assertNull(ShareIngest.gate(hasCredential = true, localOnly = false))
        assertTrue(SetupLogic.startOnSetup(hasCredential = false, localOnly = false))
    }

    @Test
    fun createWritesADirtyNoteLikeCapture() = runBlocking {
        val id = Ulid.generate()
        val mapped = ShareMapping.map(
            subject = "A page title",
            extraText = "https://example.com/path",
        )
        val outcome = ShareIngest.create(
            store = store,
            attachments = null,
            title = mapped.title,
            body = mapped.body,
            files = emptyList(),
            nowIso = "2026-09-05T12:00:00Z",
            id = id,
            enqueueSync = {},
        )
        assertEquals(ShareIngest.Outcome.Created(id), outcome)
        val note = store.read(id)!!
        val doc = Frontmatter.parse(note.wholeFileText)
        assertEquals("A page title", doc.title)
        assertEquals("https://example.com/path\n", doc.bodyText)
        // No base snapshot: the next sync treats this as a local-only new note
        // (Push), never a silent last-writer-wins overwrite.
        assertNull(store.baseOf(id))
    }

    @Test
    fun textFileBecomesAVaultAttachmentAndALink() = runBlocking {
        val id = Ulid.generate()
        val file = ShareIngest.TextFile(
            name = "clip.txt",
            mime = "text/plain",
            bytes = "shared file body\n".toByteArray(),
        )
        val outcome = ShareIngest.create(
            store = store,
            attachments = attachments,
            title = "clip",
            body = "",
            files = listOf(file),
            nowIso = "2026-09-05T12:00:00Z",
            id = id,
            enqueueSync = {},
        )
        assertTrue(outcome is ShareIngest.Outcome.Created)
        val note = store.read(id)!!
        assertTrue(note.wholeFileText.contains("[clip.txt](attachments/"))
        assertEquals(1, attDao.rows.size)
        val rel = attDao.rows.values.single().localPath!!
        assertTrue(File(root, rel).isFile)
        assertEquals("shared file body\n", File(root, rel).readText())
    }

    @Test
    fun nonTextFileIsRefusedAndWritesNothing() = runBlocking {
        val id = Ulid.generate()
        val outcome = ShareIngest.create(
            store = store,
            attachments = attachments,
            title = "photo",
            body = "",
            files = listOf(
                ShareIngest.TextFile("shot.jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte())),
            ),
            nowIso = "2026-09-05T12:00:00Z",
            id = id,
            enqueueSync = {},
        )
        assertEquals(ShareIngest.Outcome.Refused(ShareIngest.REFUSED_NOT_TEXT), outcome)
        assertNull(store.read(id))
        assertTrue(attDao.rows.isEmpty())
    }
}
