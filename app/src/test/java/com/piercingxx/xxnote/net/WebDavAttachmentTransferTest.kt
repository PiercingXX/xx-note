package com.piercingxx.xxnote.net

import com.piercingxx.xxnote.sync.PutResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WS10 binary attachment methods ([WebDavClient.putFile]/[getFile]) over
 * MockWebServer: request shape (path, Authorization, Content-Type, raw body)
 * asserted from what the server received; status mapping identical to the
 * text methods. The one-host guard is upstream and unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class WebDavAttachmentTransferTest {

    private companion object {
        const val USER = "xxnote"
        const val PASSWORD = "correct horse"
        val PAYLOAD = ByteArray(256) { (it * 7).toByte() }
    }

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            server.hostName, server.port, "/Notes/", USER, PASSWORD, scheme = "http",
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun putFileSendsRawBytesUnderAuth() {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"abc-123\""))

        val result = client.putFile("attachments/3f9a2c81b4e07d65.jpg", PAYLOAD)

        assertEquals(PutResult.WRITTEN("\"abc-123\""), result)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/Notes/attachments/3f9a2c81b4e07d65.jpg", recorded.path)
        assertEquals(
            okhttp3.Credentials.basic(USER, PASSWORD, Charsets.UTF_8),
            recorded.getHeader("Authorization"),
        )
        assertTrue(
            (recorded.getHeader("Content-Type") ?: "").startsWith("application/octet-stream"),
            "unexpected Content-Type: ${recorded.getHeader("Content-Type")}",
        )
        assertEquals(PAYLOAD.toList(), recorded.body.readByteArray().toList())
    }

    @Test
    fun putFileMapsPreconditionAndFailureStatuses() {
        server.enqueue(MockResponse().setResponseCode(412))
        assertEquals(PutResult.PRECONDITION_FAILED, client.putFile("attachments/a.jpg", PAYLOAD))

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(PutResult.FAILED, client.putFile("attachments/a.jpg", PAYLOAD))
    }

    @Test
    fun putFileTransportFailureCollapsesToFailed() {
        val deadPort = findClosedPort()
        val deadClient = WebDavClient(
            "127.0.0.1", deadPort, "/Notes/", USER, PASSWORD, scheme = "http",
        )
        assertEquals(PutResult.FAILED, deadClient.putFile("attachments/a.jpg", PAYLOAD))
    }

    @Test
    fun getFileReturnsBytesOn200() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(PAYLOAD)))

        val bytes = client.getFile("attachments/3f9a2c81b4e07d65.jpg")

        assertEquals(PAYLOAD.toList(), bytes?.toList())
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/Notes/attachments/3f9a2c81b4e07d65.jpg", recorded.path)
    }

    @Test
    fun getFileMaps404ToNullAndOtherErrorsToHttpError() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.getFile("attachments/missing.jpg"))

        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertFailsWith<HttpError> { client.getFile("attachments/private.jpg") }
        assertEquals(401, e.status)
    }

    private fun findClosedPort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }
}
