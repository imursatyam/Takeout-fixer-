package com.example

import com.example.metadata.GpsCoordinateConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsCoordinateConverterTest {

    @Test
    fun testPositiveCoordinates() {
        val data = GpsCoordinateConverter.convert(
            latitude = 37.7749,
            longitude = 122.4194,
            altitude = 15.5
        )

        assertEquals("N", data.latitudeRef)
        assertEquals("E", data.longitudeRef)
        assertEquals("0", data.altitudeRef)
        assertEquals("15500/1000", data.altitudeRational)

        // 37.7749 deg:
        // total = round(37.7749 * 3_600_000) = 135_989_640
        // deg = 37, rem = 2_789_640, min = 46, secMillis = 29_640
        assertEquals("37/1,46/1,29640/1000", data.latitudeRational)
    }

    @Test
    fun testNegativeCoordinates() {
        val data = GpsCoordinateConverter.convert(
            latitude = -33.8688,
            longitude = -71.2500,
            altitude = -5.0
        )

        assertEquals("S", data.latitudeRef)
        assertEquals("W", data.longitudeRef)
        assertEquals("1", data.altitudeRef) // Below sea level
        assertEquals("5000/1000", data.altitudeRational)
    }

    @Test
    fun testCarryCaseRounding() {
        // 10.9999999 should cleanly round to 11 degrees, 0 minutes, 0 seconds without 60 seconds overflow
        val data = GpsCoordinateConverter.convert(
            latitude = 10.9999999,
            longitude = 5.9999999
        )

        assertEquals("N", data.latitudeRef)
        assertEquals("11/1,0/1,0/1000", data.latitudeRational)
        assertEquals("E", data.longitudeRef)
        assertEquals("6/1,0/1,0/1000", data.longitudeRational)
    }

    @Test
    fun testZeroCoordinates() {
        val data = GpsCoordinateConverter.convert(
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0
        )

        assertEquals("N", data.latitudeRef)
        assertEquals("E", data.longitudeRef)
        assertEquals("0/1,0/1,0/1000", data.latitudeRational)
        assertEquals("0/1,0/1,0/1000", data.longitudeRational)
        assertEquals("0/1000", data.altitudeRational)
        assertEquals("0", data.altitudeRef)
    }
}
