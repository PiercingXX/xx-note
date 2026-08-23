package com.piercingxx.xxnote.core

import kotlin.random.Random

/**
 * ULID identifiers per the ULID specification (design D3): 26 characters of
 * Crockford Base32 ([ALPHABET] — no I, L, O, U), encoding a 48-bit
 * Unix-millisecond timestamp followed by 80 bits of randomness. Because the
 * timestamp is the high-order part, lexical order equals creation order and a
 * vault directory listing is chronological for free.
 *
 * Monotonicity: consecutive generation calls landing in the same millisecond
 * increment the previous id's randomness instead of drawing fresh bits, so ids
 * from this process never collide and never regress lexically. All entry
 * points feed one sequence guarded by a single `synchronized` monitor, making
 * [Ulid] safe for concurrent use; contention cost is irrelevant at notes-app
 * rates.
 *
 * The value is held as two Longs — [high] carries bits 127..64 of the 128-bit
 * number (`(time shl 16) or randHi`, where `randHi` is the top 16 random
 * bits) and [low] carries bits 63..0. No BigInteger anywhere.
 */
object Ulid {

    /** Crockford Base32 alphabet used by ULID; excludes I, L, O, U. */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Character length of every well-formed ULID. */
    const val LENGTH: Int = 26

    /** Maximum representable timestamp: the full unsigned 48-bit field. */
    const val MAX_TIMESTAMP: Long = (1L shl 48) - 1

    /** Generates a ULID stamped with the current wall-clock time. */
    fun generate(): String = generateAt(System.currentTimeMillis(), Random)

    /**
     * Generates a ULID stamped with [epochMilli], drawing its initial
     * randomness from [random]. When called repeatedly with the same
     * [epochMilli], the randomness portion increments monotonically rather
     * than redrawing, so a run of ids from one millisecond sorts strictly
     * increasing lexically. A call with an [epochMilli] different from the
     * previous call draws fresh randomness; monotonicity across calls is
     * therefore guaranteed for non-decreasing time requests only.
     *
     * @throws IllegalArgumentException if [epochMilli] is negative or exceeds
     *   the 48-bit timestamp range.
     * @throws IllegalStateException if incrementing would carry into the
     *   timestamp field (more than 2^80 calls within one millisecond).
     */
    fun generateAt(epochMilli: Long, random: Random = Random): String {
        require(epochMilli in 0..MAX_TIMESTAMP) {
            "timestamp out of 48-bit range: $epochMilli"
        }
        return synchronized(lock) {
            val next = if (lastMillis == epochMilli) {
                val low = lastLow + 1L
                val high = if (low == 0L) lastHigh + 1L else lastHigh
                if ((high ushr 16) != epochMilli) {
                    throw IllegalStateException(
                        "ULID randomness overflowed 80 bits within millisecond $epochMilli",
                    )
                }
                high to low
            } else {
                lastMillis = epochMilli
                fresh(epochMilli, random)
            }
            lastHigh = next.first
            lastLow = next.second
            encode(next.first, next.second)
        }
    }

    /**
     * True when [value] is a canonically encoded ULID: exactly [LENGTH]
     * characters drawn from [ALPHABET], uppercase only (lowercase and
     * Crockford lookalike transliterations such as `l`→`1` are rejected as
     * non-canonical), whose leading character keeps the timestamp inside the
     * 48-bit field.
     */
    fun isValid(value: String): Boolean {
        if (value.length != LENGTH) return false
        for ((index, c) in value.withIndex()) {
            val digit = ALPHABET.indexOf(c)
            if (digit < 0) return false
            if (index == 0 && digit > 0b111) return false
        }
        return true
    }

    /**
     * Extracts the 48-bit Unix-millisecond timestamp encoded in [value].
     *
     * @throws IllegalArgumentException if [value] is not a valid ULID per
     *   [isValid].
     */
    fun timestampOf(value: String): Long {
        require(isValid(value)) { "not a valid ULID: $value" }
        var high = 0L
        var low = 0L
        for ((index, c) in value.withIndex()) {
            val digit = if (index == 0) ALPHABET.indexOf(c) and 0b111 else ALPHABET.indexOf(c)
            high = (high shl 5) or (low ushr 59)
            low = (low shl 5) or digit.toLong()
        }
        return high ushr 16
    }

    private val lock = Any()
    private var lastMillis: Long = -1L
    private var lastHigh: Long = 0L
    private var lastLow: Long = 0L

    /** Fresh 80-bit randomness as `(high, low)` with [time] packed on top. */
    private fun fresh(time: Long, random: Random): Pair<Long, Long> {
        val first = random.nextLong()
        val second = random.nextLong()
        return ((time shl 16) or (first ushr 48)) to second
    }

    private fun encode(high: Long, low: Long): String {
        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[i] = (high ushr (56 - 8 * i)).toByte()
            bytes[8 + i] = (low ushr (56 - 8 * i)).toByte()
        }
        // MSB-first base32: char 0 carries the top 3 timestamp bits (hence
        // always '0'..'7'), chars 1..25 each carry an exact 5-bit group.
        val chars = CharArray(LENGTH)
        var byteIndex = 0
        var buffer = bytes[byteIndex].toInt() and 0xFF
        chars[0] = ALPHABET[(buffer ushr 5) and 0x7]
        var bits = 5 // unconsumed low bits of bytes[0] still in the buffer
        for (ci in 1 until LENGTH) {
            while (bits < 5) {
                byteIndex++
                buffer = (buffer shl 8) or (bytes[byteIndex].toInt() and 0xFF)
                bits += 8
            }
            bits -= 5
            chars[ci] = ALPHABET[(buffer ushr bits) and 0x1F]
        }
        return String(chars)
    }
}
