package com.piercingxx.xxnote.core

import java.security.MessageDigest

/**
 * HTTP validator (ETag) strength and the §4.2 fallback's body digest — the
 * two pure judgments both the engine and Setup need, kept in core so they are
 * proven on the JVM and can never drift between callers.
 *
 * No `android.*` imports (D6 discipline).
 */
object Etag {

    /**
     * True when [value] can guard an `If-Match` write. Null or blank means
     * the server offered no validator at all; a `W/` prefix marks a weak
     * validator, which RFC 7232 forbids using for strong comparison — a weak
     * tag on `If-Match` never matches, so honoring it would turn every write
     * into a 412. Any of those cannot lock a write; only a strong tag can.
     */
    fun isStrong(value: String?): Boolean =
        !value.isNullOrBlank() && !value.startsWith(WEAK_PREFIX)

    /** True when [value] is present but weak — unusable for locking (§4.2). */
    fun isWeak(value: String?): Boolean = value != null && value.startsWith(WEAK_PREFIX)

    /**
     * Lowercase hex SHA-256 of [text]'s UTF-8 bytes — §4.2's "full-body
     * SHA-256 on read". The fallback detector compares digests of the freshly
     * fetched remote text against the base snapshot's recorded whole-file
     * text; digest equality stands in for byte equality at a fraction of the
     * holding cost.
     */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val WEAK_PREFIX = "W/"
}
