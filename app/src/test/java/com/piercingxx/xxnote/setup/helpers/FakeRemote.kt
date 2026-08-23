package com.piercingxx.xxnote.setup.helpers

import com.piercingxx.xxnote.net.HttpError
import com.piercingxx.xxnote.sync.PutResult
import com.piercingxx.xxnote.sync.RemoteEntry
import com.piercingxx.xxnote.sync.RemoteFiles
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * Scripted [RemoteFiles] for WS6 logic tests. Mirrors WebDavClient's failure
 * surface exactly where Setup cares: unseeded listings are HTTP 404
 * ([HttpError]), unreachable paths raise plain [IOException], TLS paths raise
 * an SSL exception, and `get` maps 404 to null.
 */
class FakeRemote : RemoteFiles {

    val dirs = LinkedHashMap<String, List<RemoteEntry>>()
    val bodies = LinkedHashMap<String, String>()

    /** Paths whose listing throws [IOException] instead of answering. */
    val unreachable = LinkedHashSet<String>()

    /** Paths whose listing throws an SSL handshake failure. */
    val tlsPaths = LinkedHashSet<String>()

    /** When set, every listing throws [HttpError] with this status (auth refusal). */
    var authStatus: Int? = null

    val mkcols = mutableListOf<String>()
    private val refusedMkcols = LinkedHashSet<String>()

    fun seed(dirPath: String, entries: List<RemoteEntry>) {
        dirs[dirPath] = entries
    }

    fun body(filePath: String, text: String) {
        bodies[filePath] = text
    }

    fun failMkcol(dirPath: String) {
        refusedMkcols += dirPath
    }

    override fun list(dirPath: String): List<RemoteEntry> {
        authStatus?.let { throw HttpError(it, "PROPFIND failed: HTTP $it") }
        if (dirPath in unreachable) throw IOException("connect timed out")
        if (dirPath in tlsPaths) throw SSLHandshakeException("PKIX path building failed")
        return dirs[dirPath] ?: throw HttpError(404, "PROPFIND failed: HTTP 404")
    }

    override fun get(filePath: String): String? {
        authStatus?.let { throw HttpError(it, "GET failed: HTTP $it") }
        if (filePath in unreachable) throw IOException("connect timed out")
        return bodies[filePath]
    }

    override fun put(filePath: String, wholeFileText: String, ifMatch: String?): PutResult =
        throw UnsupportedOperationException("SetupLogic never writes files")

    override fun putIfAbsent(filePath: String, wholeFileText: String): PutResult =
        throw UnsupportedOperationException("SetupLogic never writes files")

    override fun putFile(relativePath: String, bytes: ByteArray): PutResult =
        throw UnsupportedOperationException("SetupLogic never transfers attachments")

    override fun getFile(relativePath: String): ByteArray? =
        throw UnsupportedOperationException("SetupLogic never transfers attachments")

    override fun move(fromPath: String, toPath: String, overwrite: Boolean): Boolean =
        throw UnsupportedOperationException("SetupLogic never moves files")

    override fun delete(filePath: String): Boolean =
        throw UnsupportedOperationException("SetupLogic never deletes files")

    override fun mkcol(dirPath: String): Boolean {
        mkcols += dirPath
        return dirPath !in refusedMkcols
    }
}
