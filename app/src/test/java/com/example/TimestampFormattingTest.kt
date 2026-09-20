package com.example

import com.example.metadata.ExifMetadataWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class TimestampFormattingTest {

    @Test
    fun testUtcTimestampFormatting() {
        // 2024-05-15 10:30:45 UTC
        // epoch seconds = 1715769045L
        val timestampMillis = 1715769045_000L

        val formattedDate = ExifMetadataWriter.formatExifDate(timestampMillis, ZoneOffset.UTC)
        assertEquals("2024:05:15 10:30:45", formattedDate)

        val offset = ExifMetadataWriter.formatExifOffset(timestampMillis, ZoneOffset.UTC)
        assertEquals("+00:00", offset)
    }

    @Test
    fun testCustomTimezoneFormatting() {
        val timestampMillis = 1715769045_000L // 2024-05-15 10:30:45 UTC
        val zone = ZoneId.of("+05:30")

        val formattedDate = ExifMetadataWriter.formatExifDate(timestampMillis, zone)
        // 10:30 + 5:30 = 16:00:45
        assertEquals("2024:05:15 16:00:45", formattedDate)

        val offset = ExifMetadataWriter.formatExifOffset(timestampMillis, zone)
        assertEquals("+05:30", offset)
    }

    @Test
    fun testNegativeOffsetTimezone() {
        val timestampMillis = 1715769045_000L // 2024-05-15 10:30:45 UTC
        val zone = ZoneId.of("-07:00")

        val formattedDate = ExifMetadataWriter.formatExifDate(timestampMillis, zone)
        // 10:30 - 7:00 = 03:30:45
        assertEquals("2024:05:15 03:30:45", formattedDate)

        val offset = ExifMetadataWriter.formatExifOffset(timestampMillis, zone)
        assertEquals("-07:00", offset)
    }
}
