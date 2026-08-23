package com.piercingxx.xxnote.trash

import com.piercingxx.xxnote.ui.trash.TrashMath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WS8 gate: the PURE days-remaining math behind the trash chip (D9, §12 item
 * 3). Boundaries pinned at the minute so the ceiling ruling can never drift:
 * expressed in time REMAINING before expiry, a partial day rounds UP, the
 * final 24h (inclusive) collapses to 0 ("expires today"), past expiry is 0,
 * unknown is null, and a skewed or far-future stamp caps at 7.
 */
class TrashMathTest {

    /** An arbitrary deterministic instant: 2026-08-01T00:00:00Z. */
    private val trashedAt = java.time.Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()

    /** `now` such that exactly [remaining] milliseconds remain before expiry. */
    private fun nowWithRemaining(remainingMs: Long): Long =
        trashedAt + TrashMath.EXPIRY_MS - remainingMs

    // ---- Ceiling semantics ---------------------------------------------------

    @Test
    fun freshTrashShowsSeven() {
        // Trashed this instant: exactly 7d remain — ceiling of exactly 7 is 7.
        assertEquals(7, TrashMath.daysRemaining(trashedAt, trashedAt))
    }

    @Test
    fun sixDaysTwentyThreeHoursFiftyNineMinutesRemainingIsSeven() {
        // Spec boundary: 6d23h59m LEFT (= 7d minus one minute) ceilings to 7 —
        // a partial day never understates the restore window.
        assertEquals(
            7,
            TrashMath.daysRemaining(trashedAt, nowWithRemaining(TrashMath.EXPIRY_MS - 60_000L)),
        )
    }

    @Test
    fun anyFractionAboveAFullDayRoundsUp() {
        // 4d + 1s remaining → "5d left": the chip promises whole days of life.
        assertEquals(
            5,
            TrashMath.daysRemaining(trashedAt, nowWithRemaining(4 * TrashMath.DAY_MS + 1_000L)),
        )
    }

    @Test
    fun exactlySixDaysRemainingIsSix() {
        // No fraction: ceiling of a whole number is itself.
        assertEquals(
            6,
            TrashMath.daysRemaining(trashedAt, nowWithRemaining(6 * TrashMath.DAY_MS)),
        )
    }

    @Test
    fun twentyFiveHoursRemainingCeilingsToTwo() {
        assertEquals(
            2,
            TrashMath.daysRemaining(trashedAt, nowWithRemaining(25L * 3600_000L)),
        )
    }

    // ---- The final day collapses to zero --------------------------------------

    @Test
    fun exactlyTwentyFourHoursRemainingIsZero() {
        // Inclusive boundary: the last 24h already reads as the final day.
        assertEquals(0, TrashMath.daysRemaining(trashedAt, nowWithRemaining(TrashMath.DAY_MS)))
    }

    @Test
    fun twentyThreeHoursFiftyNineMinutesRemainingIsZero() {
        assertEquals(
            0,
            TrashMath.daysRemaining(trashedAt, nowWithRemaining(23L * 3600_000L + 60_000L)),
        )
    }

    @Test
    fun sevenDaysPlusOneMinuteElapsedIsZero() {
        // Spec boundary: expired side displays as 0 ("expires today") until
        // expiry actually removes the file — never negative.
        val now = trashedAt + TrashMath.EXPIRY_MS + 60_000L
        assertEquals(0, TrashMath.daysRemaining(trashedAt, now))
    }

    @Test
    fun farPastExpiryIsStillZero() {
        val now = trashedAt + 90 * TrashMath.DAY_MS
        assertEquals(0, TrashMath.daysRemaining(trashedAt, now))
    }

    // ---- Unknown / skewed stamps ----------------------------------------------

    @Test
    fun nullStampIsUnknownNeverGuessed() {
        assertEquals(null, TrashMath.daysRemaining(null, trashedAt))
    }

    @Test
    fun clockSkewedFutureStampCapsAtSeven() {
        // A peer's stamp one hour in the future relative to our clock: clamped
        // ≥ 0 on the other edge and capped at a fresh trash's promise here.
        assertEquals(7, TrashMath.daysRemaining(trashedAt + 3600_000L, trashedAt))
    }

    @Test
    fun farFutureStampCapsAtSeven() {
        assertEquals(7, TrashMath.daysRemaining(trashedAt + 30 * TrashMath.DAY_MS, trashedAt))
    }
}
