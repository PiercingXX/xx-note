package com.piercingxx.xxnote.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictNamerTest {

    private companion object {
        val CLOCK = { Instant.parse("2026-08-23T10:04:00Z") }
        const val PATH = "/Notes/01J9F2K3M4N5P6Q7R8S9T0V1W2-grocery-list.md"
    }

    @Test
    fun `matches the D8 example format exactly`() {
        val namer = ConflictNamer("pixel9", clock = CLOCK)

        assertEquals(
            "grocery-list_pixel9_Aug-23-1004-2026_EditConflict_1.md",
            namer.forkName(PATH),
        )
    }

    @Test
    fun `generated names match the format shape`() {
        val name = ConflictNamer("pixel9", clock = CLOCK).forkName(PATH)

        assertTrue(
            Regex("^[a-z0-9-]+_[a-z0-9-]+_[A-Z][a-z]{2}-\\d{2}-\\d{4}-\\d{4}_EditConflict_\\d+\\.md$")
                .matches(name),
            "unexpected shape: $name",
        )
    }

    @Test
    fun `collision counter increments until the name is free`() {
        val namer = ConflictNamer(
            deviceName = "pixel9",
            clock = CLOCK,
            nameExists = { it.endsWith("_1.md") },
        )

        assertEquals(
            "grocery-list_pixel9_Aug-23-1004-2026_EditConflict_2.md",
            namer.forkName(PATH),
        )

        val bothTaken = ConflictNamer(
            deviceName = "pixel9",
            clock = CLOCK,
            nameExists = { !it.endsWith("_3.md") },
        )

        assertEquals(
            "grocery-list_pixel9_Aug-23-1004-2026_EditConflict_3.md",
            bothTaken.forkName(PATH),
        )
    }

    @Test
    fun `device name with spaces and non ascii characters is slug sanitized`() {
        val namer = ConflictNamer("Piercing Pixel 9 ✨ téléphone", clock = CLOCK)

        assertEquals(
            "grocery-list_piercing-pixel-9-telephone_Aug-23-1004-2026_EditConflict_1.md",
            namer.forkName(PATH),
        )
    }

    @Test
    fun `long title keeps the whole name under 255 bytes`() {
        val longTitle = "x".repeat(400)
        val path = "/Notes/01J9F2K3M4N5P6Q7R8S9T0V1W2-$longTitle.md"
        val namer = ConflictNamer("pixel9", clock = CLOCK)

        val name = namer.forkName(path)

        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255, "too long: ${name.length} chars")
        assertTrue(name.endsWith("_Aug-23-1004-2026_EditConflict_1.md"))
        assertTrue(Regex("^x+_pixel9_").containsMatchIn(name))
    }

    @Test
    fun `filename without a ulid prefix uses the whole stem`() {
        val namer = ConflictNamer("pixel9", clock = CLOCK)

        assertEquals(
            "grocery-list_pixel9_Aug-23-1004-2026_EditConflict_1.md",
            namer.forkName("/Notes/grocery-list.md"),
        )
    }

    @Test
    fun `deterministic with an injected clock`() {
        val first = ConflictNamer("pixel9", clock = CLOCK).forkName(PATH)
        val second = ConflictNamer("pixel9", clock = CLOCK).forkName(PATH)
        val laterClock = ConflictNamer("pixel9", clock = { Instant.parse("2026-08-23T14:05:00Z") })

        assertEquals(first, second)
        assertEquals(
            "grocery-list_pixel9_Aug-23-1405-2026_EditConflict_1.md",
            laterClock.forkName(PATH),
        )
    }
}
