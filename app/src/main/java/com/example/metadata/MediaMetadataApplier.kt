package com.example.metadata

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.example.model.TakeoutMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

data class ApplyResult(
    val dateFixed: Boolean,
    val gpsAdded: Boolean,
    val descriptionAdded: Boolean,
    val hasMetadata: Boolean,
    val logLine: String,
    val warnings: List<String>
)

object MediaMetadataApplier {

    private val LOG_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    suspend fun apply(
        context: Context,
        fileUri: Uri,
        fileName: String,
        metadata: TakeoutMetadata?,
        zoneId: ZoneId
    ): ApplyResult {
        if (metadata == null) {
            return ApplyResult(
                dateFixed = false,
                gpsAdded = false,
                descriptionAdded = false,
                hasMetadata = false,
                logLine = "$fileName: No sidecar JSON found, copied original file",
                warnings = emptyList()
            )
        }

        val ext = fileName.substringAfterLast('.', "").lowercase()
        val warnings = mutableListOf<String>()

        var dateFixed = false
        var gpsAdded = false
        var descAdded = false

        val pfd: ParcelFileDescriptor? = try {
            context.contentResolver.openFileDescriptor(fileUri, "rw")
        } catch (e: Exception) {
            warnings.add("Failed to open file in 'rw' mode: ${e.message}")
            null
        }

        if (pfd != null) {
            pfd.use { descriptor ->
                val fd = descriptor.fileDescriptor

                when (ext) {
                    "jpg", "jpeg", "png", "webp" -> {
                        try {
                            val exifResult = ExifMetadataWriter.writeToDescriptor(fd, metadata, zoneId)
                            dateFixed = exifResult.dateWritten
                            gpsAdded = exifResult.gpsWritten
                            descAdded = exifResult.descriptionWritten
                            warnings.addAll(exifResult.warnings)
                        } catch (e: Exception) {
                            warnings.add("EXIF embedding failed: ${e.message}")
                        }
                    }

                    "mp4", "mov", "m4v", "3gp" -> {
                        if (metadata.photoTakenTimestamp > 0L) {
                            try {
                                val patchedBoxes = Mp4CreationTimePatcher.patchFd(fd, metadata.photoTakenTimestamp)
                                if (patchedBoxes > 0) {
                                    dateFixed = true
                                } else {
                                    warnings.add("MP4 atom walker found no mvhd/tkhd/mdhd header boxes")
                                }
                            } catch (e: Exception) {
                                warnings.add("MP4 date patching failed: ${e.message}")
                            }
                        }

                        if (!metadata.description.isNullOrEmpty() || (metadata.latitude != null && metadata.longitude != null)) {
                            warnings.add("Videos cannot embed GPS/Description without full re-encoding; dropped")
                        }
                    }

                    else -> {
                        warnings.add("Format '.$ext' does not support metadata embedding; skipped embedding")
                    }
                }

                // Best-effort setLastModified
                if (metadata.photoTakenTimestamp > 0L) {
                    val modifiedOk = trySetLastModified(fileUri, descriptor, metadata.photoTakenTimestamp)
                    if (!modifiedOk) {
                        warnings.add("Could not set file lastModified time (DocumentFile best-effort)")
                    }
                }
            }
        }

        // MediaScannerConnection scanFile (with 10 s timeout)
        scanFileBestEffort(context, fileUri)

        // Build log line
        val details = mutableListOf<String>()
        if (dateFixed && metadata.photoTakenTimestamp > 0L) {
            val dateStr = Instant.ofEpochMilli(metadata.photoTakenTimestamp)
                .atZone(zoneId)
                .format(LOG_DATE_FORMATTER)
            details.add("DateTimeOriginal=$dateStr")
        }
        if (gpsAdded && metadata.latitude != null && metadata.longitude != null) {
            details.add("GPS=%.4f,%.4f".format(Locale.US, metadata.latitude, metadata.longitude))
        }
        if (descAdded) {
            details.add("Description")
        }

        val logSummary = if (details.isNotEmpty()) details.joinToString(", ") else "Metadata applied"
        val logLine = "$fileName: $logSummary"

        return ApplyResult(
            dateFixed = dateFixed,
            gpsAdded = gpsAdded,
            descriptionAdded = descAdded,
            hasMetadata = true,
            logLine = logLine,
            warnings = warnings
        )
    }

    private fun trySetLastModified(uri: Uri, pfd: ParcelFileDescriptor, timestampMillis: Long): Boolean {
        if (timestampMillis <= 0L) return false

        // 1. Try via proc fd
        try {
            val procFile = File("/proc/self/fd/${pfd.fd}")
            if (procFile.setLastModified(timestampMillis)) {
                return true
            }
        } catch (_: Throwable) {}

        // 2. Try resolving real path
        try {
            val path = resolveRealPath(uri)
            if (path != null) {
                val realFile = File(path)
                if (realFile.exists() && realFile.setLastModified(timestampMillis)) {
                    return true
                }
            }
        } catch (_: Throwable) {}

        return false
    }

    fun resolveRealPath(uri: Uri): String? {
        val uriStr = uri.toString()
        if (uri.scheme == "file") {
            return uri.path
        }

        try {
            val docId = if (DocumentsContract.isDocumentUri(null, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                uri.lastPathSegment ?: ""
            }

            if (docId.startsWith("primary:")) {
                val subPath = docId.substringAfter("primary:")
                return "/storage/emulated/0/$subPath"
            } else if (docId.contains(":")) {
                val parts = docId.split(":")
                if (parts.size == 2 && parts[0].length > 2) {
                    return "/storage/${parts[0]}/${parts[1]}"
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    private suspend fun scanFileBestEffort(context: Context, uri: Uri) {
        val path = resolveRealPath(uri) ?: return
        try {
            withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(path),
                        null
                    ) { _, _ ->
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        } catch (_: Throwable) {}
    }
}
