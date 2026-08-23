package com.piercingxx.xxnote.ui.grid

/**
 * PURE FTS query hardening for grid search (WS8; design §12 item 1, §16 R4).
 *
 * The user types free text; [sanitize] turns it into a MATCH expression the
 * Room FTS index (`note_fts`, an **FTS4** external-content table — design §11)
 * can parse without ever surfacing query syntax. Every term becomes inert:
 *
 * - Whitespace splits terms (ASCII `\s`); non-whitespace ISO control
 *   characters (\u0000–\u001F, \u007F–\u009F) are stripped outright.
 * - Double quote characters are STRIPPED from terms (ruling verified against
 *   real SQLite FTS4: unlike FTS5, FTS4 has NO doubled-quote escape inside
 *   phrases — `"say ""hi""` throws malformed-MATCH). The tokenizer treats
 *   quotes as word separators anyway, so stripping preserves exactly what
 *   the index can match while guaranteeing the expression always parses.
 * - Terms with no content character (letter, digit, `_`, anything above
 *   ASCII) are DROPPED — a term like `---` or `***` would otherwise compile
 *   to an empty phrase, and a raw `"hello` (unbalanced quote) would throw
 *   `malformed MATCH expression`.
 * - A multi-character term gets prefix matching (trailing `*`). Ruling
 *   verified against real SQLite FTS4 grammar: unlike FTS5, FTS4 ignores a
 *   `*` placed AFTER the closing quote (`"revie"*` matches nothing), so the
 *   star rides INSIDE the quotes (`"revie*"` prefixes the last token —
 *   probed on sqlite 3.x: `"budget rev*"` matches `budget review`). Single-
 *   character terms match exactly.
 * - A term made ONLY of token-safe characters (ASCII alphanumerics, `_`,
 *   anything ≥ U+0080 — CJK included) is emitted as a bareword `term*`,
 *   because the `simple` tokenizer builds ONE token per CJK run and quoted
 *   CJK phrases cannot see inside it; a bare `中国菜很*` prefixes that run.
 *   Barewords containing the uppercase operators `AND`/`OR`/`NOT`/`NEAR`
 *   (which error or misparse in FTS4 standard syntax) fall back to the
 *   quoted form instead.
 * - Column filters (`title:x`), colsets (`{title} x`), `NEAR/10`, parens,
 *   carets and hyphens lose their syntax power by construction — they end up
 *   inside quotes or tokenized away as ordinary characters.
 * - At most [MAX_TERMS] terms are kept; everything after is discarded
 *   (pathological-input insurance, not behaviour).
 *
 * An empty or fully-dropped input sanitizes to `""` — the caller treats that
 * as "clear the search".
 */
object SearchQuery {

    /** Upper bound on emitted terms per query. */
    const val MAX_TERMS = 64

    private val SPLIT = Regex("\\s+")
    private val OPERATOR_WORDS = setOf("AND", "OR", "NOT", "NEAR")

    /**
     * Free user text → safe FTS4 MATCH expression (possibly `""`). Never
     * throws; never emits an expression that can fail to parse.
     */
    fun sanitize(raw: String): String {
        val out = ArrayList<String>(MAX_TERMS)
        for (term in raw.split(SPLIT)) {
            if (out.size == MAX_TERMS) break
            val cleaned = stripControls(term).trim('*')
            if (!cleaned.any(::hasContent)) continue
            out.add(emit(cleaned))
        }
        return out.joinToString(" ")
    }

    private fun stripControls(term: String): String = buildString(term.length) {
        for (c in term) {
            if (c.isISOControl() || c == '"') continue // FTS4 has no in-phrase quote escape
            append(c)
        }
    }

    private fun hasContent(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c.code > 0x7F

    private fun isUnreserved(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c.code > 0x7F

    private fun emit(cleaned: String): String {
        val barewordSafe = cleaned.all(::isUnreserved) &&
            cleaned.uppercase() !in OPERATOR_WORDS
        return when {
            barewordSafe && cleaned.length == 1 -> cleaned
            barewordSafe -> "$cleaned*"
            cleaned.length == 1 -> "\"$cleaned\""
            else -> "\"" + cleaned + "*\"" // quotes are already stripped
        }
    }
}
