package com.piercingxx.xxnote.ui.trash

/**
 * PURE math behind the trash days-remaining chip (design §12 item 3, D9).
 * No Android, no clock reads of its own — [daysRemaining] takes `now` injected
 * so tests are deterministic.
 */
object TrashMath {

    /** Keep's rule adopted verbatim (D9): trash expires after 7 days, and expiry is the only true deletion. */
    const val EXPIRY_DAYS = 7L
    const val DAY_MS = 24L * 60L * 60L * 1000L
    const val EXPIRY_MS = EXPIRY_DAYS * DAY_MS

    /**
     * Days remaining before 7-day expiry, CEILING to whole days; 0 when expiring
     * within 24h or already past. trashedAt parsed RFC3339; null/unparseable → null
     * (rendered as "—", never guessed). now injected for determinism.
     *
     * Ceiling semantics, ruled and documented:
     * - Any fraction of a day above one rounds UP: 6d23h59m left renders "7d left".
     *   A chip never understates the time a restore still has.
     * - The final 24h is special-cased to 0 ("expires today"), inclusive boundary:
     *   exactly 24h left already counts as expiring today. Consequence, intended:
     *   nothing ever renders "1d left" — the last day speaks in words instead.
     * - Already past expiry → 0; the row stays visible until expiry actually
     *   removes it (D9: expiry is the only DELETE path).
     * - Clock skew (trashedAt stamped in the future by a peer) clamps ≥ 0 and
     *   caps at [EXPIRY_DAYS] — a skewed stamp can never promise more than a
     *   fresh trash would.
     */
    fun daysRemaining(trashedAtEpochMs: Long?, nowEpochMs: Long): Int? {
        trashedAtEpochMs ?: return null
        val remainingMs = trashedAtEpochMs + EXPIRY_MS - nowEpochMs
        if (remainingMs <= DAY_MS) return 0 // within the last 24h (inclusive) or already past
        val days = ((remainingMs + DAY_MS - 1) / DAY_MS).toInt() // ceiling, integer math
        return minOf(days, EXPIRY_DAYS.toInt())
    }
}
