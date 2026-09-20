package com.example.parser

import com.example.model.TakeoutMetadata
import org.json.JSONObject

object TakeoutJsonParser {

    /**
     * Parses a Takeout sidecar JSON string and produces a [TakeoutMetadata] object.
     * Returns null if entryPath cannot be matched to a target media file or if parsing fails.
     */
    fun parse(entryPath: String, jsonString: String): TakeoutMetadata? {
        return try {
            val root = JSONObject(jsonString)

            val title = root.optStringOrNull("title")
            val targetFilename = SidecarNameDeriver.deriveTargetPath(entryPath, title) ?: return null

            // 1. Photo taken timestamp
            val photoTakenTimeObj = root.optJSONObject("photoTakenTime")
            val creationTimeObj = root.optJSONObject("creationTime")

            val rawSeconds = photoTakenTimeObj?.optStringOrNull("timestamp")
                ?: creationTimeObj?.optStringOrNull("timestamp")

            val timestampMillis = rawSeconds?.toLongOrNull()?.let { it * 1000L } ?: 0L

            // 2. Geo location
            val geoData = root.optJSONObject("geoData") ?: root.optJSONObject("geoDataExif")
            var lat: Double? = null
            var lon: Double? = null
            var alt: Double? = null

            if (geoData != null) {
                val rawLat = geoData.optDoubleOrNull("latitude")
                val rawLon = geoData.optDoubleOrNull("longitude")
                val rawAlt = geoData.optDoubleOrNull("altitude")

                if (rawLat != null && rawLon != null) {
                    // Google placeholder check: (0, 0) means no location
                    if (rawLat != 0.0 || rawLon != 0.0) {
                        // Range check: lat in [-90, 90], lon in [-180, 180]
                        if (rawLat in -90.0..90.0 && rawLon in -180.0..180.0) {
                            lat = rawLat
                            lon = rawLon
                        }
                    }
                }

                // Treat altitude 0.0 as unknown
                if (rawAlt != null && rawAlt != 0.0) {
                    alt = rawAlt
                }
            }

            // 3. Description & URL
            val description = root.optStringOrNull("description")
            val photosUrl = root.optStringOrNull("url")

            TakeoutMetadata(
                filename = targetFilename,
                photoTakenTimestamp = timestampMillis,
                description = description,
                latitude = lat,
                longitude = lon,
                altitude = alt,
                photosUrl = photosUrl
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val str = optString(key, "")
        return if (str.isEmpty() || str == "null") null else str
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key)) return null
        val d = optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }
}
