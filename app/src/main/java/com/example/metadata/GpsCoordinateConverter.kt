package com.example.metadata

import kotlin.math.abs
import kotlin.math.round

data class GpsExifData(
    val latitudeRational: String,
    val latitudeRef: String,
    val longitudeRational: String,
    val longitudeRef: String,
    val altitudeRational: String? = null,
    val altitudeRef: String? = null
)

object GpsCoordinateConverter {

    /**
     * Converts decimal degrees (lat, lon, optional alt) into EXIF format
     * using integer milli-arc-seconds to avoid 60s/60m rounding anomalies.
     */
    fun convert(latitude: Double, longitude: Double, altitude: Double? = null): GpsExifData {
        val (latRational, latRef) = convertCoordinate(latitude, isLatitude = true)
        val (lonRational, lonRef) = convertCoordinate(longitude, isLatitude = false)

        var altRational: String? = null
        var altRef: String? = null

        if (altitude != null && !altitude.isNaN()) {
            val altMm = round(abs(altitude) * 1000.0).toLong()
            altRational = "$altMm/1000"
            altRef = if (altitude >= 0.0) "0" else "1"
        }

        return GpsExifData(
            latitudeRational = latRational,
            latitudeRef = latRef,
            longitudeRational = lonRational,
            longitudeRef = lonRef,
            altitudeRational = altRational,
            altitudeRef = altRef
        )
    }

    private fun convertCoordinate(deg: Double, isLatitude: Boolean): Pair<String, String> {
        val total = round(abs(deg) * 3_600_000.0).toLong()
        val degrees = total / 3_600_000L
        val remainder = total % 3_600_000L
        val minutes = remainder / 60_000L
        val secondsMillis = remainder % 60_000L

        val rational = "$degrees/1,$minutes/1,$secondsMillis/1000"
        val ref = if (isLatitude) {
            if (deg >= 0.0) "N" else "S"
        } else {
            if (deg >= 0.0) "E" else "W"
        }

        return Pair(rational, ref)
    }
}
