package com.piercingxx.xxnote.grid

import com.piercingxx.xxnote.ui.grid.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS8 gate: the PURE FTS query hardening. Every assertion pins the exact
 * MATCH expression emitted for a hostile or ordinary input — the sanitizer's
 * contract is that the Room FTS4 index NEVER sees live query syntax.
 * (End-to-end proof that these expressions actually run lives in
 * [FtsEscapingRoboTest]; here we pin bytes.)
 */
class SearchQueryTest {

    // ---- empties ------------------------------------------------------------

    @Test
    fun emptyAndBlankInputsSanitizeToEmpty() {
        assertEquals("", SearchQuery.sanitize(""))
        assertEquals("", SearchQuery.sanitize("     "))
        assertEquals("", SearchQuery.sanitize("\t\n\r  \u000B"))
    }

    @Test
    fun punctuationOnlyTermsAreDropped_emptyPhraseNeverEmitted() {
        // Each of these would otherwise compile to an empty phrase or a
        // malformed expression on the FTS side.
        assertEquals("", SearchQuery.sanitize("---"))
        assertEquals("", SearchQuery.sanitize("*** !!! ??? &&&"))
        assertEquals("", SearchQuery.sanitize(":: ^ | ( ) [ ] { } , /"))
        assertEquals("", SearchQuery.sanitize("\"\" '' ` ~ @ # $ %"))
    }

    // ---- ordinary terms -------------------------------------------------------

    @Test
    fun multiCharWordBecomesPrefixBareword() {
        assertEquals("oat*", SearchQuery.sanitize("oat"))
    }

    @Test
    fun casingIsPreserved() {
        assertEquals("Oat* Milk*", SearchQuery.sanitize("Oat Milk"))
    }

    @Test
    fun singleCharTermMatchesExactly_noStar() {
        assertEquals("a b", SearchQuery.sanitize("a b"))
    }

    @Test
    fun multipleTermsJoinWithImplicitAnd() {
        assertEquals("oat* milk* bin* bags*", SearchQuery.sanitize("oat milk bin bags"))
    }

    @Test
    fun digitsAndUnderscoresAreOrdinaryBarewords() {
        assertEquals("2024* logs_*", SearchQuery.sanitize("2024 logs_"))
    }

    @Test
    fun cjkRunsStayBarewordsAndGetPrefix() {
        // The simple tokenizer builds ONE token per CJK run; only a bareword
        // prefix can see into it (a quoted CJK phrase matches nothing useful).
        assertEquals("中国菜很*", SearchQuery.sanitize("中国菜很"))
        // Single CJK char: exact match, no star.
        assertEquals("中", SearchQuery.sanitize("中"))
    }

    @Test
    fun emojiRidesAsAnOrdinaryToken() {
        // Non-ASCII code points are token characters for the simple tokenizer,
        // so an emoji term is a legitimate (if odd) search word.
        assertEquals("oat* 🎉*", SearchQuery.sanitize("oat 🎉"))
    }

    // ---- syntax neutralization ---------------------------------------------------

    @Test
    fun leadingHyphenIsNeutralized_insideQuotes() {
        // A bare "-known*" would be parsed as NOT-ish/operator territory.
        assertEquals("\"-known*\"", SearchQuery.sanitize("-known"))
    }

    @Test
    fun quotesAreStrippedFromTerms_fts4HasNoInPhraseEscape() {
        // FTS4 (unlike FTS5) cannot escape quotes inside a phrase — doubling
        // throws malformed-MATCH. Quotes are word separators to the tokenizer,
        // so stripping preserves exactly what the index can match; the
        // residue becomes an ordinary bareword.
        assertEquals("say* hi*", SearchQuery.sanitize("say \"hi"))
    }

    @Test
    fun quoteOnlyTermIsDropped_notEscapedIntoAnEmptyPhrase() {
        assertEquals("", SearchQuery.sanitize("\""))
        assertEquals("", SearchQuery.sanitize("\"\" \"\""))
    }

    @Test
    fun uppercaseOperatorsAreQuotedSoTheyCannotParse() {
        // Bare AND/OR/NOT/NEAR are FTS4 operators — a bare "AND" alone throws
        // malformed-MATCH. Quoting makes them inert searchable words.
        assertEquals("\"AND*\"", SearchQuery.sanitize("AND"))
        assertEquals("\"NOT*\" oat*", SearchQuery.sanitize("NOT oat"))
    }

    @Test
    fun lowercaseOperatorWordsAreAlsoGuardedForConsistency() {
        assertEquals("\"and*\"", SearchQuery.sanitize("and"))
    }

    @Test
    fun columnFiltersColsetsAndNearLoseTheirSyntax() {
        assertEquals("\"title:x*\"", SearchQuery.sanitize("title:x"))
        assertEquals("\"{title}x*\"", SearchQuery.sanitize("{title}x"))
        assertEquals("\"NEAR/10*\"", SearchQuery.sanitize("NEAR/10"))
    }

    @Test
    fun parensCaretAndMixedPunctuationBecomePhrases() {
        assertEquals("\"(budget)*\"", SearchQuery.sanitize("(budget)"))
        assertEquals("\"^caret*\"", SearchQuery.sanitize("^caret"))
        assertEquals("\"well-known*\"", SearchQuery.sanitize("well-known"))
        assertEquals("\"don't*\"", SearchQuery.sanitize("don't"))
    }

    @Test
    fun singlePunctuationCharTermIsDropped() {
        // A lone "-" carries nothing the tokenizer can index; emitting it —
        // quoted or bare — could only produce an empty phrase or syntax.
        assertEquals("", SearchQuery.sanitize("-"))
    }

    // ---- character hygiene ---------------------------------------------------------

    @Test
    fun nonWhitespaceControlCharactersAreStripped() {
        assertEquals("abcd*", SearchQuery.sanitize("ab\u0000c\u0007d"))
        assertEquals("ok*", SearchQuery.sanitize("o\u007Fk"))
    }

    @Test
    fun controlOnlyTermsVanish() {
        assertEquals("", SearchQuery.sanitize("\u0000\u0007\u001F"))
    }

    @Test
    fun whitespaceOfEveryFlavourSplitsTerms() {
        assertEquals("oat* milk* pea* soup*", SearchQuery.sanitize("oat\nmilk\tpea\r\nsoup"))
    }

    @Test
    fun userTypedStarsCollapseToOnePrefixStar() {
        assertEquals("oat*", SearchQuery.sanitize("oat*"))
        assertEquals("oat*", SearchQuery.sanitize("**oat**"))
    }

    @Test
    fun termsAreCappedAtSixtyFour() {
        val seventy = (1..70).joinToString(" ") { "w$it" }
        val out = SearchQuery.sanitize(seventy)
        assertEquals(SearchQuery.MAX_TERMS, out.split(" ").size)
        assertTrue(out.startsWith("w1*"))
        assertTrue(out.contains("w64*"))
        assertTrue(!out.contains("w65"))
    }
}
