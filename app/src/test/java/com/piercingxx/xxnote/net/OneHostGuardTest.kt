package com.piercingxx.xxnote.net

import com.piercingxx.xxnote.sync.PutResult
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * §16 "One-host guard" + todo rule #7: the interceptor throws — it does not
 * log, warn, or fall back. The matrix covers same-host-different-port,
 * different host, an IP literal for the right machine, and a redirect to a
 * third party that must receive zero bytes.
 */
class OneHostGuardTest {

    private lateinit var server: MockWebServer
    private lateinit var otherServer: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        otherServer = MockWebServer()
        otherServer.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        otherServer.shutdown()
    }

    /** Mirrors WebDavClient's builder: redirects off, guard installed. */
    private fun guardedClient(host: String, port: Int, scheme: String = "http"): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(OneHostInterceptor(host, port, scheme))
            .build()

    @Test
    fun `correct host and port passes`() {
        server.enqueue(MockResponse().setBody("vault"))
        val client = guardedClient(server.hostName, server.port)

        val response = client.newCall(
            Request.Builder().url(server.url("/home/Drive/Notes/note.md")).build(),
        ).execute()

        assertEquals(200, response.code)
        assertEquals("vault", checkNotNull(response.body).string())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `same host on a different port throws and sends nothing`() {
        // Same machine (same host name), different listening socket.
        val client = guardedClient(server.hostName, server.port)

        val thrown = assertFailsWith<IllegalStateException> {
            client.newCall(
                Request.Builder().url(otherServer.url("/home/Drive/Notes/note.md")).build(),
            ).execute()
        }
        // The refusal names the rule, the refused request, and the origin.
        val message = checkNotNull(thrown.message)
        assert(message.startsWith("one host means one host (R8): refusing GET ")) {
            "unexpected refusal message: $message"
        }
        assert(message.contains("configured origin is http://${server.hostName}:${server.port}")) {
            "unexpected refusal message: $message"
        }
        assertEquals(0, otherServer.requestCount)
    }

    @Test
    fun `different host throws before any socket exists`() {
        val client = guardedClient(server.hostName, server.port)

        assertFailsWith<IllegalStateException> {
            client.newCall(Request.Builder().url("http://xxnote.evil.example.com/x").build()).execute()
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, otherServer.requestCount)
    }

    @Test
    fun `ip literal for the very same machine throws`() {
        // The guard is configured with one spelling; the request uses the
        // other spelling of the SAME machine. Still refused: one NAME means
        // one name.
        val configuredHost =
            if (server.hostName.equals("localhost", ignoreCase = true)) "127.0.0.1"
            else "localhost"
        val client = guardedClient(configuredHost, server.port)

        assertFailsWith<IllegalStateException> {
            client.newCall(
                Request.Builder().url(server.url("/home/Drive/Notes/note.md")).build(),
            ).execute()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cleartext downgrade of an https origin throws before any socket exists`() {
        // Origin configured https (the production default); a request tries to
        // sneak out over http to the very same host and port. Refused: one
        // SCHEME means one scheme.
        val client = guardedClient(server.hostName, server.port, scheme = "https")

        assertFailsWith<IllegalStateException> {
            client.newCall(
                Request.Builder().url(server.url("/home/Drive/Notes/note.md")).build(),
            ).execute()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `matching scheme passes with the origin it was built for`() {
        server.enqueue(MockResponse().setBody("vault"))
        val client = WebDavClient(
            server.hostName, server.port, "/home/Drive/Notes/", "xxnote", "secret", scheme = "http",
        )

        assertEquals("vault", client.get("note.md"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `redirect to a third party surfaces as failure and is never followed`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", otherServer.url("/stolen").toString()),
        )
        val client = WebDavClient(
            server.hostName, server.port, "/home/Drive/Notes/", "xxnote", "secret", scheme = "http",
        )

        assertFailsWith<IOException> { client.get("note.md") }
        assertEquals(1, server.requestCount) // our one GET arrived...
        assertEquals("GET", server.takeRequest().method)
        assertEquals(0, otherServer.requestCount) // ...and nothing left the origin
    }

    @Test
    fun `redirect makes put FAILED not followed`() {
        server.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", otherServer.url("/elsewhere").toString()),
        )
        val client = WebDavClient(
            server.hostName, server.port, "/home/Drive/Notes/", "xxnote", "secret", scheme = "http",
        )

        assertEquals(PutResult.FAILED, client.put("note.md", "# hello\n", ifMatch = null))
        assertEquals(0, otherServer.requestCount)
    }
}
