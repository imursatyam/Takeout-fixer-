package com.example.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.AppRepository
import com.example.metadata.MediaMetadataApplier
import com.example.model.JobState
import com.example.model.TakeoutMetadata
import com.example.parser.SidecarNameDeriver
import com.example.parser.TakeoutJsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class CountingInputStream(private val wrapped: InputStream) : InputStream() {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val b = wrapped.read()
        if (b != -1) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = wrapped.read(b, off, len)
        if (r > 0) bytesRead += r
        return r
    }

    override fun close() {
        wrapped.close()
    }
}

class ZipProcessor(
    private val context: Context,
    private val repository: AppRepository,
    private val zipUris: List<Uri>,
    private val destinationTreeUri: Uri,
    private val recreateFolders: Boolean,
    private val zoneId: ZoneId,
    private val isCancelled: () -> Boolean,
    private val onProgressUpdate: suspend (JobState) -> Unit
) {

    private val dirCache = mutableMapOf<String, DocumentFile>()

    suspend fun execute() = withContext(Dispatchers.IO) {
        val totalBytes = calculateTotalZipSize()
        var currentJobState = repository.getJobState() ?: JobState(
            id = 1,
            phase = "PASS_1",
            totalBytes = totalBytes,
            bytesRead = 0L
        )

        currentJobState = currentJobState.copy(totalBytes = totalBytes)
        repository.updateJobState(currentJobState)
        onProgressUpdate(currentJobState)

        // ==========================================
        // PASS 1: Metadata Cache
        // ==========================================
        if (currentJobState.phase == "IDLE" || currentJobState.phase == "PASS_1") {
            repository.insertLog("INFO", "Starting Pass 1: Parsing Takeout sidecar metadata JSONs...")
            currentJobState = currentJobState.copy(phase = "PASS_1")
            repository.updateJobState(currentJobState)
            onProgressUpdate(currentJobState)

            var pass1Bytes = 0L
            val batch = mutableListOf<TakeoutMetadata>()

            for ((index, zipUri) in zipUris.withIndex()) {
                if (isCancelled()) throw CancellationException("Job cancelled by user")

                val fileName = getZipDisplayName(zipUri) ?: "zip-${index + 1}"
                repository.insertLog("INFO", "Pass 1 [${index + 1}/${zipUris.size}]: Scanning $fileName")

                val rawStream = context.contentResolver.openInputStream(zipUri)
                    ?: continue
                val countingStream = CountingInputStream(rawStream)
                val buffered = BufferedInputStream(countingStream, 64 * 1024)
                val zipIn = ZipInputStream(buffered)

                try {
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        if (isCancelled()) throw CancellationException("Job cancelled by user")

                        if (!entry.isDirectory) {
                            val entryName = entry.name
                            if (SidecarNameDeriver.isPotentialSidecar(entryName)) {
                                val jsonStr = readEntryStringBounded(zipIn, 2 * 1024 * 1024)
                                val metadata = TakeoutJsonParser.parse(entryName, jsonStr)
                                if (metadata != null) {
                                    batch.add(metadata)
                                    if (batch.size >= 500) {
                                        repository.insertMetadataBatch(batch)
                                        batch.clear()
                                    }
                                }
                            }
                        }

                        zipIn.closeEntry()

                        // Update progress periodically
                        val currentRead = pass1Bytes + countingStream.bytesRead
                        currentJobState = currentJobState.copy(
                            bytesRead = (currentRead / 2), // Half progress allocated to Pass 1
                            currentFile = entry?.name ?: ""
                        )
                        onProgressUpdate(currentJobState)

                        entry = zipIn.nextEntry
                    }
                } finally {
                    zipIn.close()
                    pass1Bytes += countingStream.bytesRead
                }
            }

            // Flush remaining batch
            if (batch.isNotEmpty()) {
                repository.insertMetadataBatch(batch)
                batch.clear()
            }

            repository.insertLog("SUCCESS", "Pass 1 finished: Metadata database ready.")
            currentJobState = currentJobState.copy(phase = "PASS_2", bytesRead = totalBytes / 2)
            repository.updateJobState(currentJobState)
            onProgressUpdate(currentJobState)
        }

        // ==========================================
        // PASS 2: Extract & Apply
        // ==========================================
        if (currentJobState.phase == "PASS_2") {
            repository.insertLog("INFO", "Starting Pass 2: Extracting media and applying metadata...")
            val rootDoc = DocumentFile.fromTreeUri(context, destinationTreeUri)
                ?: throw IllegalStateException("Cannot access destination directory")
            dirCache[""] = rootDoc

            var pass2Bytes = 0L
            val halfBytes = totalBytes / 2

            for ((index, zipUri) in zipUris.withIndex()) {
                if (isCancelled()) throw CancellationException("Job cancelled by user")

                val zipDisplayName = getZipDisplayName(zipUri) ?: "zip-${index + 1}"
                repository.insertLog("INFO", "Pass 2 [${index + 1}/${zipUris.size}]: Extracting $zipDisplayName")

                val rawStream = context.contentResolver.openInputStream(zipUri)
                    ?: continue
                val countingStream = CountingInputStream(rawStream)
                val buffered = BufferedInputStream(countingStream, 64 * 1024)
                val zipIn = ZipInputStream(buffered)

                try {
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        if (isCancelled()) throw CancellationException("Job cancelled by user")

                        val entryName = entry.name
                        if (!entry.isDirectory && !SidecarNameDeriver.isPotentialSidecar(entryName) && !SidecarNameDeriver.isAlbumLevelFile(entryName)) {
                            // Check if already processed (resume support)
                            val alreadyDone = repository.isEntryProcessed(entryName)
                            if (alreadyDone) {
                                zipIn.closeEntry()
                                entry = zipIn.nextEntry
                                continue
                            }

                            // Look up metadata
                            val metadata = findMetadataForMedia(entryName)

                            // Copy file and apply metadata
                            var partialDoc: DocumentFile? = null
                            try {
                                val targetDoc = getOrCreateTargetFile(rootDoc, entryName)
                                partialDoc = targetDoc

                                // Copy with 8 KB buffer
                                val outStream = context.contentResolver.openOutputStream(targetDoc.uri)
                                    ?: throw IllegalStateException("Cannot open output stream for ${targetDoc.uri}")

                                val buffer = ByteArray(8192)
                                outStream.use { out ->
                                    var read = zipIn.read(buffer)
                                    while (read != -1) {
                                        if (isCancelled()) {
                                            out.flush()
                                            throw CancellationException("Job cancelled during copy")
                                        }
                                        out.write(buffer, 0, read)
                                        read = zipIn.read(buffer)
                                    }
                                    out.flush()
                                }

                                // Apply metadata in-place
                                val applyResult = MediaMetadataApplier.apply(
                                    context = context,
                                    fileUri = targetDoc.uri,
                                    fileName = entryName.substringAfterLast('/'),
                                    metadata = metadata,
                                    zoneId = zoneId
                                )

                                repository.markEntryProcessed(entryName, "COMPLETED", applyResult.logLine)
                                repository.insertLog(
                                    if (applyResult.hasMetadata) "SUCCESS" else "INFO",
                                    applyResult.logLine
                                )

                                for (w in applyResult.warnings) {
                                    repository.insertLog("WARN", "${entryName.substringAfterLast('/')}: $w")
                                }

                                currentJobState = currentJobState.copy(
                                    filesProcessed = currentJobState.filesProcessed + 1,
                                    datesFixed = currentJobState.datesFixed + (if (applyResult.dateFixed) 1 else 0),
                                    gpsAdded = currentJobState.gpsAdded + (if (applyResult.gpsAdded) 1 else 0),
                                    descriptionsAdded = currentJobState.descriptionsAdded + (if (applyResult.descriptionAdded) 1 else 0),
                                    filesWithoutJson = currentJobState.filesWithoutJson + (if (!applyResult.hasMetadata) 1 else 0),
                                    currentFile = entryName.substringAfterLast('/')
                                )
                            } catch (e: CancellationException) {
                                // Delete partial file on cancel
                                try {
                                    partialDoc?.delete()
                                } catch (_: Throwable) {}
                                throw e
                            } catch (e: Exception) {
                                repository.markEntryProcessed(entryName, "FAILED", e.message)
                                repository.insertLog("ERROR", "${entryName.substringAfterLast('/')}: Failed: ${e.message}")
                                currentJobState = currentJobState.copy(
                                    filesSkippedOrFailed = currentJobState.filesSkippedOrFailed + 1,
                                    currentFile = entryName.substringAfterLast('/')
                                )
                            }
                        }

                        zipIn.closeEntry()

                        // Update progress bar
                        val currentRead = pass2Bytes + countingStream.bytesRead
                        currentJobState = currentJobState.copy(
                            bytesRead = halfBytes + (currentRead / 2)
                        )
                        repository.updateJobState(currentJobState)
                        onProgressUpdate(currentJobState)

                        entry = zipIn.nextEntry
                    }
                } finally {
                    zipIn.close()
                    pass2Bytes += countingStream.bytesRead
                }
            }

            currentJobState = currentJobState.copy(
                phase = "COMPLETED",
                bytesRead = totalBytes
            )
            repository.updateJobState(currentJobState)
            onProgressUpdate(currentJobState)
            repository.insertLog("SUCCESS", "Job finished: All files extracted and metadata restored!")
        }
    }

    private suspend fun findMetadataForMedia(entryPath: String): TakeoutMetadata? {
        // 1. Exact full path
        val exact = repository.findMetadata(entryPath)
        if (exact != null) return exact

        // 2. For names ending in "-edited" before extension:
        val lastSlash = entryPath.lastIndexOf('/')
        val parent = if (lastSlash >= 0) entryPath.substring(0, lastSlash) else ""
        val fileName = if (lastSlash >= 0) entryPath.substring(lastSlash + 1) else entryPath

        val dotIdx = fileName.lastIndexOf('.')
        val base = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
        val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""

        if (base.endsWith("-edited", ignoreCase = true)) {
            val originalBase = base.substring(0, base.length - "-edited".length)
            val originalFileName = "$originalBase$ext"
            val originalPath = if (parent.isNotEmpty()) "$parent/$originalFileName" else originalFileName
            return repository.findMetadata(originalPath)
        }

        return null
    }

    private fun getOrCreateTargetFile(rootDoc: DocumentFile, entryPath: String): DocumentFile {
        val fileName = entryPath.substringAfterLast('/')
        val parentPath = if (entryPath.contains('/')) entryPath.substringBeforeLast('/') else ""

        val targetDir = if (recreateFolders && parentPath.isNotEmpty()) {
            getOrCreateDirectory(rootDoc, parentPath)
        } else {
            rootDoc
        }

        val mimeType = getMimeType(fileName)
        // If file already exists, reuse or create unique
        val existing = targetDir.findFile(fileName)
        if (existing != null) {
            return existing
        }
        return targetDir.createFile(mimeType, fileName)
            ?: throw IllegalStateException("Could not create file $fileName in destination")
    }

    private fun getOrCreateDirectory(rootDoc: DocumentFile, relativePath: String): DocumentFile {
        val cached = dirCache[relativePath]
        if (cached != null && cached.exists()) return cached

        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var current = rootDoc
        var currentPath = ""

        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val nextCached = dirCache[currentPath]
            if (nextCached != null && nextCached.exists()) {
                current = nextCached
            } else {
                val found = current.findFile(part) ?: current.createDirectory(part)
                ?: throw IllegalStateException("Failed to create directory $part")
                dirCache[currentPath] = found
                current = found
            }
        }

        return current
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
    }

    private fun readEntryStringBounded(zipIn: ZipInputStream, maxBytes: Int): String {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalRead = 0
        while (true) {
            val toRead = minOf(buffer.size, maxBytes - totalRead)
            if (toRead <= 0) break
            val read = zipIn.read(buffer, 0, toRead)
            if (read == -1) break
            baos.write(buffer, 0, read)
            totalRead += read
        }
        return baos.toString("UTF-8")
    }

    private fun calculateTotalZipSize(): Long {
        var total = 0L
        for (uri in zipUris) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    total += pfd.statSize
                }
            } catch (_: Exception) {}
        }
        return if (total > 0L) total else 1L
    }

    private fun getZipDisplayName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) it.getString(nameIndex) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
