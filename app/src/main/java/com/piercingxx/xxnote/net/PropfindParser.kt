package com.piercingxx.xxnote.net

import com.piercingxx.xxnote.sync.RemoteEntry
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * A structurally broken multistatus body (malformed or truncated XML). An
 * [IOException] so it rides WebDavClient's documented failure surface: the
 * engine treats it as a failed listing, NEVER as an empty vault.
 */
class ParseException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/**
 * Parses a WebDAV 207 Multi-Status body into [RemoteEntry] rows, using
 * Android's built-in XmlPullParser (org.xmlpull.v1 — zero dependencies,
 * D17).
 *
 * Failure direction is split by scope:
 * - **Per entry** — one broken href (an undecodable %-sequence, say) skips
 *   just that entry; the healthy entries around it still parse. Degrade,
 *   never discard the vault.
 * - **Whole document** — a structural XML failure (truncated body, not-XML
 *   bytes) throws [ParseException]; an unparseable 207 must never masquerade
 *   as "empty folder" to the engine's trash-safety gate.
 *
 * Namespace prefixes are tolerated in any spelling (`D:`, `d:`, or none)
 * because only element local names matter. Subcollections are kept as
 * [RemoteEntry.collection] rows so their presence can be disclosed (P2.10:
 * subfolders are not synced — plain words at Setup confirm), while the
 * REQUESTED directory itself is dropped via [excludeEncodedPath]: a Depth:1
 * listing always contains the folder asked about, and the vault root is not
 * a subfolder of itself.
 *
 * ETags are kept VERBATIM (quoting marks and any `W/` weak marker intact):
 * the value goes back on the wire inside `If-Match` exactly as the server
 * produced it, so this parser must not "normalize" it. Weakness is visible
 * to callers in the raw string; RemoteEntry deliberately carries no separate
 * weakness flag.
 */
object PropfindParser {

    /**
     * Parse a multistatus document. Structural XML trouble throws
     * [ParseException]; only individual bad entries are skipped.
     * [excludeEncodedPath] names the requested collection (its %-encoded
     * absolute path — what [com.piercingxx.xxnote.net.WebDavClient] sent);
     * that one row is never emitted.
     */
    fun parse(xml: String, excludeEncodedPath: String? = null): List<RemoteEntry> {
        // Not-XML bytes (an empty or HTML error body wearing a 207): the
        // pull parser would accept some of these as an empty document, which
        // must never masquerade as an empty vault.
        if (!xml.trimStart().startsWith("<")) {
            throw ParseException("multistatus body is not XML")
        }
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            parseResponses(parser, excludeEncodedPath)
        } catch (e: Exception) {
            throw ParseException("unparseable multistatus body", e)
        }
    }

    private fun parseResponses(
        parser: XmlPullParser,
        excludeEncodedPath: String?,
    ): List<RemoteEntry> {
        val entries = mutableListOf<RemoteEntry>()
        var href: String? = null
        var etag: String? = null
        var size: Long? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "response" -> {
                        href = null
                        etag = null
                        size = null
                    }
                    "href" -> href = parser.nextText()
                    "getetag" -> etag = parser.nextText()
                    "getcontentlength" ->
                        size = parser.nextText()?.trim()?.toLongOrNull()
                }
                XmlPullParser.END_TAG -> if (parser.localName() == "response") {
                    entry(href, etag, size, excludeEncodedPath)?.let(entries::add)
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun entry(
        href: String?,
        etag: String?,
        size: Long?,
        excludeEncodedPath: String?,
    ): RemoteEntry? {
        if (href == null) return null
        val isCollection = href.endsWith("/")
        val clean = href.substringBefore('?').trimEnd('/')
        val segment = clean.substringAfterLast('/')
        if (segment.isEmpty()) return null
        // One undecodable segment skips ONE entry, not the listing.
        val fileName = try {
            decodeSegment(segment)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (isCollection) {
            // The vault root answering its own PROPFIND is not a subfolder.
            // Compare path only: some servers (IIS/mod_dav) return an
            // absolute `https://host/vault/` href while the request path is
            // `/vault/` — scheme and authority must not participate.
            if (excludeEncodedPath != null && hrefPath(clean) == hrefPath(excludeEncodedPath)) {
                return null
            }
            return RemoteEntry(fileName = fileName, etag = null, sizeBytes = null, collection = true)
        }
        return RemoteEntry(
            fileName = fileName,
            etag = etag?.trim()?.ifEmpty { null },
            sizeBytes = size,
        )
    }

    /** Path of an href, comparable across encodings and absolute vs path-only forms. */
    private fun hrefPath(href: String): String {
        val noQuery = href.substringBefore('?').trim()
        val path = if ("://" in noQuery) {
            noQuery.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
        } else {
            noQuery
        }
        return decodedPath(path)
    }

    /** Decoded `/`-joined segments of an href path — comparable across encodings. */
    private fun decodedPath(encodedPath: String): String =
        encodedPath.split('/').filter { it.isNotEmpty() }.joinToString("/") { decodeSegment(it) }

    /**
     * Percent-decoding for one path segment. `+` is preserved as a literal
     * plus (it means space only in query strings), while `%20`, `%2B`, CJK
     * escapes, etc. decode normally — DSM %-encodes every href it returns.
     */
    private fun decodeSegment(segment: String): String =
        try {
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        } catch (_: UnsupportedEncodingException) {
            segment
        }

    /** Local name whether or not the factory processed namespaces. */
    private fun XmlPullParser.localName(): String = name.substringAfter(':')
}
