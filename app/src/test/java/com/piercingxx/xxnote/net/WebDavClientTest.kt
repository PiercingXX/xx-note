package com.piercingxx.xxnote.net

import com.piercingxx.xxnote.sync.PutResult
import com.piercingxx.xxnote.sync.RemoteEntry
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §4.2 method-by-method behavior over MockWebServer: request shape (method,
 * path, Depth, Authorization, If-Match/If-None-Match, Destination) asserted
 * from what the server actually received. Robolectric supplies the platform
 * XmlPullParser that PropfindParser runs on, exactly as on device.
 */
@RunWith(RobolectricTestRunner::class)
class WebDavClientTest {

    private companion object {
        const val USER = "xxnote"
        const val PASSWORD = "correct horse"
        const val BASE = "/home/Drive/Notes/"

        // DSM-style multistatus: D: prefix, %-encoded hrefs, the collection
        // itself first, then one real file.
        val DSM_MULTISTATUS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/home/Drive/Notes/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                    <D:getlastmodified>Mon, 23 Aug 2026 09:00:00 GMT</D:getlastmodified>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/home/Drive/Notes/grocery%20list.md</D:href>
                <D:propstat>
                  <D:prop>
                    <D:getetag>&quot;1a2b-3c4d&quot;</D:getetag>
                    <D:getcontentlength>512</D:getcontentlength>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val EXPECTED_ENTRY =
            RemoteEntry(fileName = "grocery list.md", etag = "\"1a2b-3c4d\"", sizeBytes = 512L)
    }

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            server.hostName, server.port, BASE, USER, PASSWORD, scheme = "http",
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `propfind sends depth one and authorization and parses entries`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(DSM_MULTISTATUS))

        val entries = client.list("")

        assertEquals(listOf(EXPECTED_ENTRY), entries)
        val recorded = server.takeRequest()
        assertEquals("PROPFIND", recorded.method)
        assertEquals(BASE, recorded.path)
        assertEquals("1", recorded.getHeader("Depth"))
        assertEquals(
            Credentials.basic(USER, PASSWORD, Charsets.UTF_8),
            recorded.getHeader("Authorization"),
        )
        val body = recorded.body.readUtf8()
        assert(body.contains("<d:propfind")) { "unexpected PROPFIND body:\n$body" }
        assert(body.contains("<d:getetag/>")) { "etag not requested in:\n$body" }
    }

    @Test
    fun `get returns body on 200 and null on 404`() {
        server.enqueue(MockResponse().setBody("# note body\n"))
        assertEquals("# note body\n", client.get("01J9F2-note.md"))

        val got = server.takeRequest()
        assertEquals("GET", got.method)
        assertEquals("${BASE}01J9F2-note.md", got.path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.get("missing.md"))
        assertEquals("${BASE}missing.md", server.takeRequest().path)
    }

    @Test
    fun `put 204 is WRITTEN and carries verbatim If-Match`() {
        server.enqueue(
            MockResponse().setResponseCode(204).setHeader("ETag", "\"dead-beef\""),
        )

        val result = client.put("note.md", "# rewritten\n", ifMatch = "W/\"weak-1\"")

        assertEquals(PutResult.WRITTEN("\"dead-beef\""), result)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("${BASE}note.md", recorded.path)
        assertEquals("W/\"weak-1\"", recorded.getHeader("If-Match"))
        assertEquals("# rewritten\n", recorded.body.readUtf8())
        assert(recorded.getHeader("Content-Type")!!.startsWith("text/markdown"))
    }

    @Test
    fun `put without ifMatch sends no conditionals`() {
        server.enqueue(MockResponse().setResponseCode(201))
        assertEquals(PutResult.WRITTEN(null), client.put("note.md", "body", ifMatch = null))
        assertNull(server.takeRequest().getHeader("If-Match"))
    }

    @Test
    fun `put 412 is PRECONDITION_FAILED`() {
        server.enqueue(MockResponse().setResponseCode(412))
        assertEquals(
            PutResult.PRECONDITION_FAILED,
            client.put("note.md", "body", ifMatch = "\"stale\""),
        )
    }

    @Test
    fun `put other failure status is FAILED`() {
        server.enqueue(MockResponse().setResponseCode(409)) // missing parent folder
        assertEquals(PutResult.FAILED, client.put("no/such/dir.md", "body", ifMatch = null))
    }

    @Test
    fun `putIfAbsent sends If-None-Match star`() {
        server.enqueue(
            MockResponse().setResponseCode(201).setHeader("ETag", "\"fresh-1\""),
        )

        assertEquals(PutResult.WRITTEN("\"fresh-1\""), client.putIfAbsent("new note.md", "first body"))

        val recorded = server.takeRequest()
        assertEquals("*", recorded.getHeader("If-None-Match"))
        assertEquals("${BASE}new%20note.md", recorded.path) // space encoded on the wire
    }

    @Test
    fun `move sends absolute Destination header and default Overwrite T`() {
        server.enqueue(MockResponse().setResponseCode(201))

        val moved = client.move("note.md", ".xxnote/trash/note.md")

        assertEquals(true, moved)
        val recorded = server.takeRequest()
        assertEquals("MOVE", recorded.method)
        assertEquals(
            "http://${server.hostName}:${server.port}${BASE}.xxnote/trash/note.md",
            recorded.getHeader("Destination"),
        )
        assertEquals("T", recorded.getHeader("Overwrite"))
        assertEquals("${BASE}note.md", recorded.path)
    }

    @Test
    fun `trash move sends Overwrite F so an occupied destination is refused not clobbered`() {
        server.enqueue(MockResponse().setResponseCode(412))

        val moved = client.move(
            "note.md",
            ".xxnote/trash/note.md",
            overwrite = false,
        )

        assertEquals(false, moved)
        val recorded = server.takeRequest()
        assertEquals("F", recorded.getHeader("Overwrite"))
        assertEquals("${BASE}note.md", recorded.path) // MOVE targets the source URL
    }

    @Test
    fun `list throws on non-207 so an empty listing cannot be faked`() {
        // A wrong vault path must NOT look like an empty vault to the engine.
        server.enqueue(MockResponse().setResponseCode(404))

        assertFailsWith<IOException> { client.list("") }
        assert(server.takeRequest().method == "PROPFIND")
    }

    @Test
    fun `get throws HttpError on auth refusal`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val thrown = assertFailsWith<HttpError> { client.get("note.md") }
        assertEquals(401, thrown.status)
        assert(thrown.message!!.contains("HTTP 401"))
    }

    @Test
    fun `delete maps 204 true 404 true 500 false`() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(true, client.delete("expired.md"))

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(true, client.delete("already-gone.md"))

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(false, client.delete("stuck.md"))
    }

    @Test
    fun `mkcol maps 201 true 405 true 409 false`() {
        server.enqueue(MockResponse().setResponseCode(201))
        assertEquals(true, client.mkcol(".xxnote"))

        val created = server.takeRequest()
        assertEquals("MKCOL", created.method)
        assertEquals("${BASE}.xxnote/", created.path) // collection gets trailing slash

        server.enqueue(MockResponse().setResponseCode(405)) // already exists
        assertEquals(true, client.mkcol(".xxnote"))

        server.enqueue(MockResponse().setResponseCode(409)) // parent missing
        assertEquals(false, client.mkcol("a/b/c"))
    }

    @Test
    fun `transport failure collapses to FAILED and false`() {
        server.shutdown()

        assertEquals(PutResult.FAILED, client.put("note.md", "body", ifMatch = null))
        assertEquals(false, client.delete("note.md"))
        assertEquals(false, client.mkcol("dir"))
        assertEquals(false, client.move("a.md", "b.md"))
    }

    @Test
    fun `list throws HttpError carrying the status on non-207 so an empty listing cannot be faked`() {
        // A wrong vault path must NOT look like an empty vault to the engine.
        server.enqueue(MockResponse().setResponseCode(404))

        val thrown = assertFailsWith<HttpError> { client.list("") }
        assertEquals(404, thrown.status)
        assert(thrown.message!!.contains("HTTP 404")) { "status must stay in the message (R10)" }
        assert(server.takeRequest().method == "PROPFIND")
    }
}
