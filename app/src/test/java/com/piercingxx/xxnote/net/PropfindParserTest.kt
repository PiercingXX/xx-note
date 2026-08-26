package com.piercingxx.xxnote.net

import com.piercingxx.xxnote.sync.RemoteEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §16: PROPFIND parse against realistic DSM-style multistatus fixtures.
 * Runs under Robolectric so the platform's XmlPullParser implementation
 * (what PropfindParser uses on device) is on the classpath.
 */
@RunWith(RobolectricTestRunner::class)
class PropfindParserTest {

    @Test
    fun `dsm style uppercase D namespace parses`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <D:multistatus xmlns:D="DAV:">
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

        assertEquals(
            listOf(RemoteEntry("grocery list.md", "\"1a2b-3c4d\"", 512L)),
            PropfindParser.parse(xml),
        )
    }

    @Test
    fun `lowercase d namespace parses`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/vault/idea.md</d:href>
                <d:propstat><d:prop>
                  <d:getetag>"ef01"</d:getetag>
                  <d:getcontentlength>7</d:getcontentlength>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        assertEquals(
            listOf(RemoteEntry("idea.md", "\"ef01\"", 7L)),
            PropfindParser.parse(xml),
        )
    }

    @Test
    fun `no namespace prefix parses`() {
        val xml = """
            <multistatus xmlns="DAV:">
              <response>
                <href>/vault/plain.md</href>
                <propstat><prop>
                  <getetag>"p1"</getetag>
                  <getcontentlength>3</getcontentlength>
                </prop></propstat>
              </response>
            </multistatus>
        """.trimIndent()

        assertEquals(
            listOf(RemoteEntry("plain.md", "\"p1\"", 3L)),
            PropfindParser.parse(xml),
        )
    }

    @Test
    fun `subcollections are disclosed minus the requested folder itself`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/home/Drive/Notes/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/home/Drive/Notes/.xxnote/trash/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/home/Drive/Notes/photos/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/home/Drive/Notes/real.md</D:href>
                <D:propstat><D:prop><D:getetag>"r"</D:getetag></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        // P2.10: subfolders ride along flagged so their presence can be
        // disclosed; the vault root answering its own PROPFIND never does.
        assertEquals(
            listOf(
                RemoteEntry("trash", null, null, collection = true),
                RemoteEntry("photos", null, null, collection = true),
                RemoteEntry("real.md", "\"r\"", null),
            ),
            PropfindParser.parse(xml, excludeEncodedPath = "/home/Drive/Notes/"),
        )
    }

    @Test
    fun `empty folder yields empty list`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/home/Drive/Notes/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals(emptyList(), PropfindParser.parse(xml, excludeEncodedPath = "/home/Drive/Notes/"))
    }

    @Test
    fun `absolute href of the requested folder is still excluded`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>https://nas.example.ts.net:5006/home/Drive/Notes/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>https://nas.example.ts.net:5006/home/Drive/Notes/photos/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>https://nas.example.ts.net:5006/home/Drive/Notes/real.md</D:href>
                <D:propstat><D:prop><D:getetag>"r"</D:getetag></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals(
            listOf(
                RemoteEntry("photos", null, null, collection = true),
                RemoteEntry("real.md", "\"r\"", null),
            ),
            PropfindParser.parse(xml, excludeEncodedPath = "/home/Drive/Notes/"),
        )
    }

    @Test
    fun `structural xml failure throws ParseException never a fake empty vault`() {
        // Truncated mid-entry, empty body, not-XML bytes: all structural, so
        // all must surface as ParseException (an IOException) — an unparseable
        // 207 must never masquerade as "empty folder" for trash safety.
        assertFailsWith<ParseException> { PropfindParser.parse("<D:multistatus><D:response><D:href>trunc") }
        assertFailsWith<ParseException> { PropfindParser.parse("") }
        assertFailsWith<ParseException> { PropfindParser.parse("not xml at all <<<>>") }
    }

    @Test
    fun `bad percent escape skips just that entry and neighbors still parse`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/v/good%20notes.md</D:href>
                <D:propstat><D:prop><D:getetag>"g1"</D:getetag></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/v/broken%zzescape.md</D:href>
                <D:propstat><D:prop><D:getetag>"b1"</D:getetag></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/v/also%2Bfine.md</D:href>
                <D:propstat><D:prop/></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals(
            listOf(
                RemoteEntry("good notes.md", "\"g1\"", null),
                RemoteEntry("also+fine.md", null, null),
            ),
            PropfindParser.parse(xml),
        )
    }

    @Test
    fun `weak etag is kept verbatim`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/v/w.md</D:href>
                <D:propstat><D:prop><D:getetag>W/&quot;weak-1&quot;</D:getetag></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        // Kept exactly as the server produced it — weakness visible, no
        // normalization (it must round-trip into If-Match untouched).
        assertEquals(
            listOf(RemoteEntry("w.md", "W/\"weak-1\"", null)),
            PropfindParser.parse(xml),
        )
    }

    @Test
    fun `percent escapes decode and literal plus survives`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/v/c%2B%2B%20notes%20%E6%B3%A8.md</D:href>
                <D:propstat><D:prop/></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        // %20 → space, %2B → +, CJK escapes decoded; a bare '+' in an href
        // would stay '+' (plus means space only in query strings).
        assertEquals(
            listOf(RemoteEntry("c++ notes 注.md", null, null)),
            PropfindParser.parse(xml),
        )
    }

    @Test
    fun `garbage size parses to null and missing href skips the entry`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/v/sized.md</D:href>
                <D:propstat><D:prop><D:getcontentlength>abc</D:getcontentlength></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:propstat><D:prop><D:getetag>"orphan"</D:getetag></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertEquals(
            listOf(RemoteEntry("sized.md", null, null)),
            PropfindParser.parse(xml),
        )
    }
}
