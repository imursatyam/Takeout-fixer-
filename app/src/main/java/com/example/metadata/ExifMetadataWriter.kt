package com.example.metadata

import androidx.exifinterface.media.ExifInterface
import com.example.model.TakeoutMetadata
import java.io.FileDescriptor
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class ExifWriteResult(
    val dateWritten: Boolean,
    val gpsWritten: Boolean,
    val descriptionWritten: Boolean,
    val warnings: List<String>
)

object ExifMetadataWriter {

    private val EXIF_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val GPS_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US)
    private val GPS_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    /**
     * Formats an epoch millisecond timestamp into EXIF date string in the given ZoneId.
     */
    fun formatExifDate(timestampMillis: Long, zoneId: ZoneId): String {
        val instant = Instant.ofEpochMilli(timestampMillis)
        return instant.atZone(zoneId).format(EXIF_DATE_FORMATTER)
    }

    /**
     * Formats the timezone offset for the given timestamp (e.g. "+05:30", "-08:00").
     */
    fun formatExifOffset(timestampMillis: Long, zoneId: ZoneId): String {
        val instant = Instant.ofEpochMilli(timestampMillis)
        val offset = instant.atZone(zoneId).offset
        val totalSeconds = offset.totalSeconds
        val sign = if (totalSeconds >= 0) "+" else "-"
        val absSec = abs(totalSeconds)
        val hours = absSec / 3600
        val minutes = (absSec % 3600) / 60
        return String.format(Locale.US, "%s%02d:%02d", sign, hours, minutes)
    }

    /**
     * Writes metadata to the provided FileDescriptor using ExifInterface.
     */
    fun writeToDescriptor(
        fileDescriptor: FileDescriptor,
        metadata: TakeoutMetadata,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ExifWriteResult {
        val exif = ExifInterface(fileDescriptor)
        val warnings = mutableListOf<String>()

        var dateWritten = false
        var gpsWritten = false
        var descWritten = false

        // 1. Timestamps
        if (metadata.photoTakenTimestamp > 0L) {
            val dateStr = formatExifDate(metadata.photoTakenTimestamp, zoneId)
            val offsetStr = formatExifOffset(metadata.photoTakenTimestamp, zoneId)

            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)

            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offsetStr)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetStr)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offsetStr)

            dateWritten = true
        }

        // 2. Description & Comments
        if (!metadata.description.isNullOrEmpty()) {
            var desc = metadata.description
            val hasNonAscii = desc.any { it.code > 127 }
            if (hasNonAscii) {
                warnings.add("Description contains non-ASCII characters; ExifInterface may store as ASCII")
            }

            if (desc.length > 4000) {
                desc = desc.substring(0, 4000)
                warnings.add("Description truncated to 4000 characters")
            }

            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, desc)
            descWritten = true
        }

        if (!metadata.photosUrl.isNullOrEmpty()) {
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, metadata.photosUrl)
        }

        // 3. GPS Coordinates
        if (metadata.latitude != null && metadata.longitude != null) {
            val gpsData = GpsCoordinateConverter.convert(
                latitude = metadata.latitude,
                longitude = metadata.longitude,
                altitude = metadata.altitude
            )

            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, gpsData.latitudeRational)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, gpsData.latitudeRef)
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, gpsData.longitudeRational)
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, gpsData.longitudeRef)

            if (gpsData.altitudeRational != null && gpsData.altitudeRef != null) {
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, gpsData.altitudeRational)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, gpsData.altitudeRef)
            }

            // GPS datestamp/timestamp in UTC
            if (metadata.photoTakenTimestamp > 0L) {
                val utcZoned = Instant.ofEpochMilli(metadata.photoTakenTimestamp).atZone(ZoneOffset.UTC)
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, utcZoned.format(GPS_DATE_FORMATTER))
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, utcZoned.format(GPS_TIME_FORMATTER))
            }

            gpsWritten = true
        }

        exif.saveAttributes()

        return ExifWriteResult(
            dateWritten = dateWritten,
            gpsWritten = gpsWritten,
            descriptionWritten = descWritten,
            warnings = warnings
        )
    }
}
