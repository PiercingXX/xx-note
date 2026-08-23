package com.piercingxx.xxnote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UlidTest {

    private val format = Regex("^[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{26}$")

    @Test
    fun `generated ids match the canonical 26-char Crockford format`() {
        for (time in listOf(0L, 1L, 1_700_000_000_000L, Ulid.MAX_TIMESTAMP)) {
            val id = Ulid.generateAt(time, kotlin.random.Random(1))
            assertTrue(Ulid.isValid(id), "invalid id for time $time: $id")
            assertTrue(format.matches(id), "format mismatch for time $time: $id")
            for (banned in "ILOU") {
                assertEquals(-1, id.indexOf(banned), "id contains $banned: $id")
            }
        }
        assertTrue(format.matches(Ulid.generate()))
    }

    @Test
    fun `isValid accepts a known-good canonical id`() {
        val good = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        assertTrue(Ulid.isValid(good))
    }

    @Test
    fun `isValid rejects wrong charset`() {
        val good = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        for (bad in listOf('I', 'L', 'O', 'U')) {
            val mutated = good.dropLast(1) + bad
            assertTrue(!Ulid.isValid(mutated), "'$mutated' should be rejected")
        }
    }

    @Test
    fun `isValid rejects wrong length`() {
        val good = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        for (bad in listOf("", good.take(25), good + "A", good.drop(13))) {
            assertTrue(!Ulid.isValid(bad), "'$bad' should be rejected")
        }
    }

    @Test
    fun `uppercase-only alphabet passes`() {
        val upper = "01J9F2K3M4N5P6Q7R8S9T0V1W2"
        assertTrue(Ulid.isValid(upper))
    }

    @Test
    fun `isValid rejects timestamps beyond 48 bits`() {
        // First character carries the top 3 timestamp bits, so it must be <= '7'.
        assertTrue(!Ulid.isValid("8AAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertTrue(!Ulid.isValid("ZZZZZZZZZZZZZZZZZZZZZZZZZZ"))
        assertTrue(Ulid.isValid("7ZZZZZZZZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun `timestampOf round-trips generateAt`() {
        for (seed in intArrayOf(1, 42, 2026)) {
            for (time in listOf(0L, 1L, 1234567L, 1_700_000_000_123L, Ulid.MAX_TIMESTAMP)) {
                val id = Ulid.generateAt(time, kotlin.random.Random(seed))
                assertEquals(time, Ulid.timestampOf(id))
            }
        }
    }

    @Test
    fun `timestampOf rejects invalid input`() {
        assertFailsWith<IllegalArgumentException> { Ulid.timestampOf("not-a-ulid") }
        assertFailsWith<IllegalArgumentException> { Ulid.timestampOf("") }
    }

    @Test
    fun `generateAt rejects out-of-range timestamps`() {
        assertFailsWith<IllegalArgumentException> { Ulid.generateAt(-1L) }
        assertFailsWith<IllegalArgumentException> { Ulid.generateAt(Ulid.MAX_TIMESTAMP + 1) }
    }

    @Test
    fun `thousand ids in one millisecond sort strictly increasing`() {
        val sameMilli = 1_700_000_001_234L
        val rng = kotlin.random.Random(7)
        val ids = List(1000) { Ulid.generateAt(sameMilli, rng) }
        for (i in 0 until ids.size - 1) {
            assertTrue(ids[i] < ids[i + 1], "order broke at $i: ${ids[i]} !< ${ids[i + 1]}")
        }
    }

    @Test
    fun `ten thousand seeded generations are unique`() {
        val rng = kotlin.random.Random(42)
        val base = 1_700_000_002_000L
        val ids = HashSet<String>(10_000)
        repeat(10_000) { i ->
            val id = Ulid.generateAt(base + (i % 97), rng)
            assertTrue(Ulid.isValid(id))
            ids += id
        }
        assertEquals(10_000, ids.size)
    }

    @Test
    fun `lexical order equals creation order across milliseconds`() {
        val rng = kotlin.random.Random(11)
        val base = 1_700_000_003_000L
        var previousId = ""
        var previousTime = -1L
        repeat(500) { i ->
            val time = base + i / 5
            val id = Ulid.generateAt(time, rng)
            assertTrue(previousId < id, "$previousId should sort before $id")
            if (i > 0 && time != previousTime) {
                assertTrue(
                    Ulid.timestampOf(previousId) < Ulid.timestampOf(id),
                    "timestamps regressed between $previousId and $id",
                )
            }
            previousId = id
            previousTime = time
        }
    }

    @Test
    fun `generate produces well-formed current-era ids`() {
        repeat(100) {
            val id = Ulid.generate()
            assertTrue(format.matches(id), "bad id from generate(): $id")
            assertTrue(
                Ulid.timestampOf(id) in 1_600_000_000_000L..2_100_000_000_000L,
                "implausible timestamp in $id",
            )
        }
    }
}
