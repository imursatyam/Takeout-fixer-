package com.example.parser

object SidecarNameDeriver {
    private const val FULL_MARKER = "supplemental-metadata"

    // Regex matches: <mediaPart>.<marker>[(counter)].json (case-insensitive)
    // group 1: mediaPart
    // group 2: marker
    // group 3: counter (optional)
    private val SIDECAR_REGEX = Regex("""^(.*)\.([a-zA-Z0-9_-]+?)(?:\((\d+)\))?\.json$""", RegexOption.IGNORE_CASE)

    /**
     * Checks if this file is an album-level metadata or non-sidecar file.
     */
    fun isAlbumLevelFile(entryPath: String): Boolean {
        val fileName = entryPath.substringAfterLast('/')
        val lower = fileName.lowercase()
        return lower == "metadata.json" ||
                lower == "shared_album_metadata.json" ||
                lower == "user_generated_content_metadata.json" ||
                lower.startsWith("print-subscriptions") ||
                lower.startsWith("album_")
    }

    /**
     * Derives the target media file path (ZIP-relative) from the sidecar JSON entry path.
     * Returns null if this entry is not a valid sidecar JSON.
     *
     * @param entryPath Full ZIP path to the JSON file
     * @param jsonTitle Optional title field from inside the JSON, used as fallback
     */
    fun deriveTargetPath(entryPath: String, jsonTitle: String? = null): String? {
        if (isAlbumLevelFile(entryPath)) return null
        if (!entryPath.endsWith(".json", ignoreCase = true)) return null

        val lastSlash = entryPath.lastIndexOf('/')
        val parentDir = if (lastSlash >= 0) entryPath.substring(0, lastSlash) else ""
        val fileName = if (lastSlash >= 0) entryPath.substring(lastSlash + 1) else entryPath

        val match = SIDECAR_REGEX.matchEntire(fileName)
        if (match != null) {
            val mediaPart = match.groupValues[1]
            val marker = match.groupValues[2]
            val counter = match.groupValues[3] // may be empty if no (n)

            if (marker.isNotEmpty() && FULL_MARKER.startsWith(marker.lowercase())) {
                val targetFileName = if (counter.isNotEmpty()) {
                    val dotIdx = mediaPart.lastIndexOf('.')
                    if (dotIdx > 0) {
                        val base = mediaPart.substring(0, dotIdx)
                        val ext = mediaPart.substring(dotIdx)
                        "$base($counter)$ext"
                    } else {
                        "$mediaPart($counter)"
                    }
                } else {
                    mediaPart
                }

                return if (parentDir.isNotEmpty()) "$parentDir/$targetFileName" else targetFileName
            }
        }

        // Fallback to title field if available
        if (!jsonTitle.isNullOrBlank()) {
            val cleanTitle = jsonTitle.trim()
            return if (parentDir.isNotEmpty()) "$parentDir/$cleanTitle" else cleanTitle
        }

        return null
    }

    /**
     * Checks whether an entry path has the structure of a sidecar JSON,
     * without needing to parse the JSON content first.
     */
    fun isPotentialSidecar(entryPath: String): Boolean {
        if (isAlbumLevelFile(entryPath)) return false
        if (!entryPath.endsWith(".json", ignoreCase = true)) return false

        val fileName = entryPath.substringAfterLast('/')
        val match = SIDECAR_REGEX.matchEntire(fileName) ?: return false
        val marker = match.groupValues[2]
        return marker.isNotEmpty() && FULL_MARKER.startsWith(marker.lowercase())
    }
}
