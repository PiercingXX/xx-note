package com.piercingxx.xxnote.util

import android.media.ExifInterface
import java.io.IOException

/**
 * §10, unconditional privacy rule: original EXIF location data is stripped on
 * insert, before hashing, before the first byte ever reaches the vault or the
 * NAS. There is no setting; a photo that cannot be rewritten without its GPS
 * block is not stored at all (an unstored photo beats a leaked location).
 *
 * Only the GPS IFD is touched. Orientation and `DateTimeOriginal` survive —
 * both matter downstream (correct rendering; sort order in other readers).
 *
 * [ExifInterface] rewrites require a seekable file, so callers run this on a
 * temp file during the insert pipeline (write-temp → strip → hash → rename),
 * which also means the content address is computed over the STRIPPED bytes:
 * the stripped copy is the only copy that ever exists.
 */
object ExifStripper {

    /**
     * Every GPS tag [ExifInterface] declares, as plain strings (compile-time
     * constants of the framework class, safe to reference from pure-JVM code).
     * The test seam for "the strip list is complete and GPS-only".
     */
    fun gpsTagNames(): List<String> = listOf(
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
    )

    /**
     * Removes every [gpsTagNames] attribute from the image at [filePath], in
     * place, preserving everything else (orientation, timestamps, camera
     * make/model are deliberately kept). Framework [ExifInterface] exposes no
     * `removeAttribute`; setting an attribute to null is its documented
     * removal path. Absent tags are no-ops.
     *
     * @throws IOException when the file is not an EXIF-writable image or the
     *   rewrite fails — callers must abort the insert rather than store the
     *   unstripped bytes.
     */
    @Throws(IOException::class)
    fun strip(filePath: String) {
        val exif = ExifInterface(filePath)
        gpsTagNames().forEach { tag -> exif.setAttribute(tag, null) }
        exif.saveAttributes()
    }
}
