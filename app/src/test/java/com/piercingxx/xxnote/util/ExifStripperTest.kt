package com.piercingxx.xxnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM half of the strip contract: the GPS tag list is complete,
 * GPS-only, and does NOT swallow orientation or DateTimeOriginal (§10 strips
 * location unconditionally and nothing else).
 */
class ExifStripperTest {

    @Test
    fun gpsTagListIsPresent() {
        val tags = ExifStripper.gpsTagNames()
        assertTrue(tags.isNotEmpty())
        assertEquals(tags.size, tags.distinct().size)
    }

    @Test
    fun everyListedTagIsAGpsTag() {
        ExifStripper.gpsTagNames().forEach { tag ->
            assertTrue("not a GPS tag: $tag", tag.startsWith("GPS"))
        }
    }

    @Test
    fun latitudeLongitudeTimestampsAreCovered() {
        val tags = ExifStripper.gpsTagNames()
        for (required in listOf("GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSDateStamp", "GPSTimeStamp", "GPSSatellites")) {
            assertTrue("missing $required", required in tags)
        }
    }

    @Test
    fun orientationAndDateTimeOriginalAreNotInTheStripList() {
        val tags = ExifStripper.gpsTagNames()
        assertFalse("Orientation" in tags)
        assertFalse("DateTimeOriginal" in tags)
    }

    @Test
    fun tagNamesMatchTheKnownExifSpellings() {
        // Compile-time constants of android.media.ExifInterface fold to plain
        // strings on the JVM — assert a few verbatim spellings.
        val tags = ExifStripper.gpsTagNames()
        assertTrue("GPSLatitude" in tags)
        assertTrue("GPSTimeStamp" in tags)
        assertTrue("GPSProcessingMethod" in tags)
        assertTrue("GPSVersionID" in tags)
    }
}
