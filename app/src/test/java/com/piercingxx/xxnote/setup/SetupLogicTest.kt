package com.piercingxx.xxnote.setup

import com.piercingxx.xxnote.net.HttpError
import com.piercingxx.xxnote.setup.helpers.FakeRemote
import com.piercingxx.xxnote.sync.PutResult
import com.piercingxx.xxnote.sync.RemoteEntry
import com.piercingxx.xxnote.sync.SyncGraph.SETTING_DEVICE_NAME
import com.piercingxx.xxnote.ui.setup.SetupLogic
import com.piercingxx.xxnote.ui.setup.SetupLogic.Endpoint
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WS6 pure logic, JVM only: disclosure wording, prefix-candidate selection,
 * persisted-config payload assembly, and ETag-mode detection — driven by a
 * scripted [FakeRemote], no Android, no network.
 */
class SetupLogicTest {

    // ---- step 5: the §12 import disclosure -------------------------------------

    @Test
    fun `disclosure text is exactly the specified words`() {
        assertEquals(
            "47 existing .md files — these will be imported, ids assigned where missing, nothing will be overwritten",
            SetupLogic.disclosureText(47),
        )
    }

    @Test
    fun `empty folder discloses as empty folder`() {
        assertEquals("empty folder", SetupLogic.disclosureText(0))
        assertEquals("nothing to import", SetupLogic.idlessLine(found = 0, idLess = 0))
    }

    @Test
    fun `id-less detail line counts what will receive ids`() {
        assertEquals(
            "3 of 47 have no id yet — those receive one on import",
            SetupLogic.idlessLine(found = 47, idLess = 3),
        )
    }

    // ---- step 4: prefix candidates -------------------------------------------------

    @Test
    fun `probe marks existing prefixes in order and skips missing ones`() {
        val remote = FakeRemote().apply {
            seed("home", listOf(RemoteEntry("a.md", "\"e1\"", 10L)))
            seed("home/Drive", emptyList())
            // home/Drive/Notes, Drive, Notes → unseeded = HTTP 404
        }

        val probes = SetupLogic.probePrefixes(remote)

        assertIs<SetupLogic.Reach.Reachable>(probes["home"])
        assertIs<SetupLogic.Reach.Reachable>(probes["home/Drive"])
        assertIs<SetupLogic.Reach.Refused>(probes["Drive"])
        assertEquals(404, (probes["Drive"] as SetupLogic.Reach.Refused).status)
        assertEquals(listOf("home", "home/Drive"), SetupLogic.existingFolders(probes))
    }

    @Test
    fun `unreachable candidate is distinct from a clean 404`() {
        val remote = FakeRemote().apply { unreachable += "home" }

        val probes = SetupLogic.probePrefixes(remote)

        assertIs<SetupLogic.Reach.Unreachable>(probes["home"])
        assertIs<SetupLogic.Reach.Refused>(probes["Drive"])
        assertTrue(SetupLogic.existingFolders(probes).isEmpty())
    }

    @Test
    fun `ensureFolders mkcols every level and fails when any level is refused`() {
        val ok = FakeRemote()
        assertTrue(SetupLogic.ensureFolders(ok, "home/Drive/Notes"))
        assertEquals(listOf("home", "home/Drive", "home/Drive/Notes"), ok.mkcols)

        val refused = FakeRemote().apply { failMkcol("home/Drive") }
        assertTrue(!SetupLogic.ensureFolders(refused, "home/Drive/Notes"))
        assertEquals(3, refused.mkcols.size)
    }

    // ---- step 1: endpoint --------------------------------------------------------------

    @Test
    fun `endpoint rejects blanks cleartext junk ports and stray colons`() {
        assertNull(SetupLogic.endpointProblem("nas.ts.net", "5006"))
        assertNull(SetupLogic.endpointProblem("https://nas.ts.net", ""))
        assertNull(SetupLogic.endpointProblem("nas.ts.net", ""))

        assertTrue(SetupLogic.endpointProblem("", "") != null)
        assertTrue(SetupLogic.endpointProblem("http://nas.ts.net", "") != null)
        assertTrue(SetupLogic.endpointProblem("nas ts.net", "") != null)
        assertTrue(SetupLogic.endpointProblem("nas/x.ts.net", "") != null)
        assertTrue(SetupLogic.endpointProblem("nas.ts.net", "0") != null)
        assertTrue(SetupLogic.endpointProblem("nas.ts.net", "70000") != null)
        assertTrue(SetupLogic.endpointProblem("nas.ts.net", "abc") != null)
        assertTrue(SetupLogic.endpointProblem("a:b:c", "") != null)
    }

    @Test
    fun `endpoint parses host suffix and port-field precedence`() {
        assertEquals(Endpoint("NAS.ts.net", 5006), SetupLogic.endpoint("https://NAS.ts.net", ""))
        assertEquals(Endpoint("nas.ts.net", 443), SetupLogic.endpoint("nas.ts.net:443", ""))
        assertEquals(Endpoint("nas.ts.net", 9001), SetupLogic.endpoint("nas.ts.net", "9001"))
        // The explicit port field wins over a ":port" suffix in the host field.
        assertEquals(Endpoint("nas.ts.net", 9001), SetupLogic.endpoint("nas.ts.net:443", "9001"))
    }

    // ---- step 4 input normalization ---------------------------------------------------------

    @Test
    fun `base path normalizes slashes and refuses dot segments`() {
        assertEquals("home/Drive/Notes", SetupLogic.normalizeBasePath("/home/Drive/Notes/"))
        assertEquals("home/Drive", SetupLogic.normalizeBasePath(" home//Drive "))
        assertNull(SetupLogic.normalizeBasePath(""))
        assertNull(SetupLogic.normalizeBasePath("/"))
        assertNull(SetupLogic.normalizeBasePath(".xxnote"))
        assertNull(SetupLogic.normalizeBasePath("Drive/.hidden"))
        assertNull(SetupLogic.normalizeBasePath("a\\b"))
    }

    // ---- step 5 counting + §4.2 ETag detection -----------------------------------------------

    @Test
    fun `confirm counts markdown files id-less ones and etag mode in one pass`() {
        val remote = FakeRemote().apply {
            seed(
                "home/Drive",
                listOf(
                    RemoteEntry("grocery.md", "\"e1\"", 10L),
                    RemoteEntry("no-id.md", "\"e2\"", 20L),
                    RemoteEntry(".hidden.md", "\"e3\"", 30L),
                    RemoteEntry("readme.txt", "\"e4\"", 40L),
                ),
            )
            body("home/Drive/grocery.md", doc(id = "01J9F2EXAMPLEEXAMPLEEXAMPLE0"))
            body("home/Drive/no-id.md", doc(id = null))
        }

        val scan = SetupLogic.confirmFolder(remote, "home/Drive")

        assertEquals(2, scan.found)
        assertEquals(1, scan.idLess)
        assertEquals(SetupLogic.ETAG_MODE_ETAG, scan.etagMode)
    }

    @Test
    fun `etag mode falls back when any entry or the whole folder lacks getetag`() {
        assertEquals(
            SetupLogic.ETAG_MODE_ETAG,
            SetupLogic.etagModeOf(listOf(RemoteEntry("a.md", "\"x\"", 1L), RemoteEntry("b.md", "\"y\"", 2L))),
        )
        assertEquals(
            SetupLogic.ETAG_MODE_FALLBACK,
            SetupLogic.etagModeOf(listOf(RemoteEntry("a.md", "\"x\"", 1L), RemoteEntry("b.md", null, 2L))),
        )
        assertEquals(SetupLogic.ETAG_MODE_FALLBACK, SetupLogic.etagModeOf(emptyList()))
    }

    @Test
    fun `id-less means no non-blank frontmatter id`() {
        assertTrue(SetupLogic.isIdLess(doc(id = null)))
        assertTrue(SetupLogic.isIdLess(doc(id = "")))
        assertTrue(SetupLogic.isIdLess("just body, no frontmatter\n"))
        assertTrue(!SetupLogic.isIdLess(doc(id = "01J9F2EXAMPLEEXAMPLEEXAMPLE0")))
    }

    // ---- step 3 classification -----------------------------------------------------------------

    @Test
    fun `reach distinguishes reachable refused tls and unreachable`() {
        val fine = FakeRemote().apply { seed("", emptyList()) }
        assertIs<SetupLogic.Reach.Reachable>(SetupLogic.reach(fine))

        val auth = FakeRemote().apply { authStatus = 401 }
        val refused = SetupLogic.reach(auth)
        assertIs<SetupLogic.Reach.Refused>(refused)
        assertEquals(401, (refused as SetupLogic.Reach.Refused).status)

        val tls = FakeRemote().apply { tlsPaths += "" }
        assertIs<SetupLogic.Reach.TlsFailure>(SetupLogic.reach(tls))

        val dead = FakeRemote().apply { unreachable += "" }
        assertIs<SetupLogic.Reach.Unreachable>(SetupLogic.reach(dead))
    }

    @Test
    fun `describe speaks plain words that separate offline from http refusal`() {
        val host = "nas.tailnet.ts.net"
        assertTrue(
            SetupLogic.describe(SetupLogic.Reach.Unreachable(null), host)
                .contains("no answer from $host"),
        )
        assertTrue(
            SetupLogic.describe(SetupLogic.Reach.Refused(401, null), host).contains("HTTP 401"),
        )
        assertTrue(
            SetupLogic.describe(SetupLogic.Reach.TlsFailure(null), host).contains("certificate"),
        )
        assertTrue(
            SetupLogic.describe(SetupLogic.Reach.Reachable(emptyList(), ""), host) == "connected — the server answered",
        )
    }

    // ---- step 7 persistence payload ----------------------------------------------------------------

    @Test
    fun `config payload assembles credential row and setting rows`() {
        val payload = SetupLogic.configPayload(
            endpoint = Endpoint("nas.tailnet.ts.net", 5006),
            basePath = "home/Drive/Notes",
            user = "xxnote",
            deviceName = "Pixel 9",
            etagMode = SetupLogic.ETAG_MODE_FALLBACK,
        )

        // SyncGraph's documented convention: the credential table has no port column.
        assertEquals("nas.tailnet.ts.net:5006", payload.credentialHost)
        assertEquals("home/Drive/Notes", payload.basePath)
        assertEquals("xxnote", payload.user)

        val settings = payload.settings.toMap()
        assertEquals("nas.tailnet.ts.net", settings[SetupLogic.KEY_HOST])
        assertEquals("5006", settings[SetupLogic.KEY_PORT])
        assertEquals("home/Drive/Notes", settings[SetupLogic.KEY_BASE_PATH])
        assertEquals("xxnote", settings[SetupLogic.KEY_USER])
        assertEquals("Pixel 9", settings[SetupLogic.KEY_DEVICE_NAME])
        // The device-name key MUST stay aligned with what the engine reads.
        assertEquals(SETTING_DEVICE_NAME, SetupLogic.KEY_DEVICE_NAME)
        assertEquals(SetupLogic.ETAG_MODE_FALLBACK, settings[SetupLogic.KEY_ETAG_MODE])
        assertEquals(6, payload.settings.size)
    }

    // ---- outcome words ---------------------------------------------------------------------------------

    @Test
    fun `outcome lines carry verbatim status and per-verdict counts`() {
        assertTrue(SetupLogic.authReopenLines(401)[0].contains("HTTP 401"))

        val summary = SetupLogic.completedSummary(
            pulled = 47, pushed = 0, merged = 2, forked = 1, trashed = 0, resurrected = 0, nothing = 12,
        )
        assertTrue(summary.contains("pulled 47"))
        assertTrue(summary.contains("merged 2"))
        assertTrue(summary.contains("unchanged 12"))

        assertTrue(SetupLogic.haltedLines(wouldTrash = 3, liveNotes = 4)[1].contains("3 of 4"))
    }

    // ---- step 6 ------------------------------------------------------------------------------------------

    @Test
    fun `device name falls back through model then constant`() {
        assertEquals("Pixel 9", SetupLogic.deviceNameOrDefault("Pixel 9", "unused"))
        assertEquals("Pixel 9", SetupLogic.deviceNameOrDefault("  ", "Pixel 9"))
        assertEquals("xx-device", SetupLogic.deviceNameOrDefault("", ""))
        assertEquals("my pixel", SetupLogic.deviceNameOrDefault("my   pixel ", "m"))
    }

    /** A minimal whole `.md` file with an optional frontmatter `id:` line. */
    private fun doc(id: String?): String = buildString {
        append("---\n")
        if (id != null) append("id: $id\n")
        append("title: t\n---\nbody\n")
    }
}
