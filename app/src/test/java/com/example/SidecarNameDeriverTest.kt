package com.example

import com.example.parser.SidecarNameDeriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarNameDeriverTest {

    @Test
    fun testStandardSidecarDerivation() {
        val entry = "Takeout/Google Photos/Photos from 2026/IMG_1.jpg.supplemental-metadata.json"
        val target = SidecarNameDeriver.deriveTargetPath(entry)
        assertEquals("Takeout/Google Photos/Photos from 2026/IMG_1.jpg", target)
    }

    @Test
    fun testTruncatedMarkerDerivation() {
        // Takeout truncates long filenames such as .supplemental-metad.json or .supplemental-m.json
        val entry1 = "Takeout/Google Photos/2024/very_long_file_name.jpg.supplemental-metad.json"
        val target1 = SidecarNameDeriver.deriveTargetPath(entry1)
        assertEquals("Takeout/Google Photos/2024/very_long_file_name.jpg", target1)

        val entry2 = "vacation/beach_sunset.png.supp.json"
        val target2 = SidecarNameDeriver.deriveTargetPath(entry2)
        assertEquals("vacation/beach_sunset.png", target2)
    }

    @Test
    fun testDuplicateCounterMovesBeforeExtension() {
        // "IMG_1.jpg.supplemental-metadata(1).json" belongs to "IMG_1(1).jpg"
        val entry = "Takeout/Google Photos/IMG_1.jpg.supplemental-metadata(1).json"
        val target = SidecarNameDeriver.deriveTargetPath(entry)
        assertEquals("Takeout/Google Photos/IMG_1(1).jpg", target)

        val videoEntry = "videos/clip.mp4.supplemental-metadata(2).json"
        val videoTarget = SidecarNameDeriver.deriveTargetPath(videoEntry)
        assertEquals("videos/clip(2).mp4", videoTarget)
    }

    @Test
    fun testDuplicateCounterWithTruncatedMarker() {
        val entry = "Photos/PANO_2020.jpg.supplemental-meta(3).json"
        val target = SidecarNameDeriver.deriveTargetPath(entry)
        assertEquals("Photos/PANO_2020(3).jpg", target)
    }

    @Test
    fun testAlbumLevelFilesIgnored() {
        assertNull(SidecarNameDeriver.deriveTargetPath("Takeout/Google Photos/metadata.json"))
        assertNull(SidecarNameDeriver.deriveTargetPath("metadata.json"))
        assertNull(SidecarNameDeriver.deriveTargetPath("Takeout/shared_album_metadata.json"))
        assertTrue(SidecarNameDeriver.isAlbumLevelFile("Takeout/Google Photos/metadata.json"))
        assertFalse(SidecarNameDeriver.isPotentialSidecar("metadata.json"))
    }

    @Test
    fun testFallbackToTitle() {
        val entry = "Takeout/Photos/unknown_name.json"
        val target = SidecarNameDeriver.deriveTargetPath(entry, jsonTitle = "original_photo.jpg")
        assertEquals("Takeout/Photos/original_photo.jpg", target)
    }
}
