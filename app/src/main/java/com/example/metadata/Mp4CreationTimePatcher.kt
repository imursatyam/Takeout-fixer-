package com.example.metadata

import android.system.Os
import java.io.FileDescriptor

interface RandomAccessIO {
    fun length(): Long
    fun read(offset: Long, buffer: ByteArray, length: Int): Int
    fun write(offset: Long, buffer: ByteArray, length: Int): Int
}

class FdRandomAccessIO(private val fd: FileDescriptor) : RandomAccessIO {
    override fun length(): Long {
        val stat = Os.fstat(fd)
        return stat.st_size
    }

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        return Os.pread(fd, buffer, 0, length, offset)
    }

    override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
        return Os.pwrite(fd, buffer, 0, length, offset)
    }
}

class ByteArrayRandomAccessIO(val bytes: ByteArray) : RandomAccessIO {
    override fun length(): Long = bytes.size.toLong()

    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        val start = offset.toInt()
        if (start < 0 || start >= bytes.size) return 0
        val toRead = minOf(length, bytes.size - start)
        System.arraycopy(bytes, start, buffer, 0, toRead)
        return toRead
    }

    override fun write(offset: Long, buffer: ByteArray, length: Int): Int {
        val start = offset.toInt()
        if (start < 0 || start >= bytes.size) return 0
        val toWrite = minOf(length, bytes.size - start)
        System.arraycopy(buffer, 0, bytes, start, toWrite)
        return toWrite
    }
}

object Mp4CreationTimePatcher {

    // Seconds between 1904-01-01 and 1970-01-01
    const val SECONDS_1904_TO_1970: Long = 2_082_844_800L

    /**
     * Patches creation_time and modification_time in-place on a FileDescriptor.
     * Supported extensions: MP4, MOV, M4V, 3GP.
     *
     * @return Number of boxes successfully patched (mvhd, tkhd, mdhd)
     */
    fun patchFd(fd: FileDescriptor, photoTakenTimestampMillis: Long): Int {
        val io = FdRandomAccessIO(fd)
        return patch(io, photoTakenTimestampMillis)
    }

    /**
     * Patches creation_time and modification_time in-place on any [RandomAccessIO].
     */
    fun patch(io: RandomAccessIO, photoTakenTimestampMillis: Long): Int {
        val seconds1904 = (photoTakenTimestampMillis / 1000L) + SECONDS_1904_TO_1970
        val fileLength = io.length()
        if (fileLength < 8) return 0

        return walkBoxes(io, startOffset = 0L, endOffset = fileLength, seconds1904 = seconds1904)
    }

    private fun walkBoxes(
        io: RandomAccessIO,
        startOffset: Long,
        endOffset: Long,
        seconds1904: Long
    ): Int {
        var currentOffset = startOffset
        var patchCount = 0

        val headerBuf = ByteArray(8)
        val largeSizeBuf = ByteArray(8)

        while (currentOffset + 8 <= endOffset) {
            val bytesRead = io.read(currentOffset, headerBuf, 8)
            if (bytesRead < 8) break

            val rawSize = readUint32(headerBuf, 0)
            val type = String(headerBuf, 4, 4, Charsets.US_ASCII)

            var boxSize: Long
            var headerSize: Long

            when (rawSize) {
                1L -> {
                    // 64-bit large size
                    if (currentOffset + 16 > endOffset) break
                    io.read(currentOffset + 8, largeSizeBuf, 8)
                    boxSize = readUint64(largeSizeBuf, 0)
                    headerSize = 16L
                }
                0L -> {
                    // Box extends to end of file/container
                    boxSize = endOffset - currentOffset
                    headerSize = 8L
                }
                else -> {
                    boxSize = rawSize
                    headerSize = 8L
                }
            }

            if (boxSize < headerSize || currentOffset + boxSize > endOffset) {
                // Malformed box or corrupt boundary
                break
            }

            val payloadStart = currentOffset + headerSize
            val payloadEnd = currentOffset + boxSize

            when (type) {
                "moov", "trak", "mdia" -> {
                    // Recurse into container boxes
                    patchCount += walkBoxes(io, payloadStart, payloadEnd, seconds1904)
                }
                "mvhd", "tkhd", "mdhd" -> {
                    // Patch header box
                    if (patchHeaderBox(io, payloadStart, payloadEnd, seconds1904)) {
                        patchCount++
                    }
                }
            }

            currentOffset += boxSize
        }

        return patchCount
    }

    private fun patchHeaderBox(
        io: RandomAccessIO,
        payloadStart: Long,
        payloadEnd: Long,
        seconds1904: Long
    ): Boolean {
        // FullBox header: 1 byte version, 3 bytes flags
        if (payloadEnd - payloadStart < 4) return false

        val versionBuf = ByteArray(1)
        io.read(payloadStart, versionBuf, 1)
        val version = versionBuf[0].toInt() and 0xFF

        return when (version) {
            0 -> {
                // Version 0: 4-byte creation_time, 4-byte modification_time
                if (payloadEnd - payloadStart < 12) return false
                val timeVal = seconds1904 and 0xFFFFFFFFL
                writeUint32(io, payloadStart + 4, timeVal)
                writeUint32(io, payloadStart + 8, timeVal)
                true
            }
            1 -> {
                // Version 1: 8-byte creation_time, 8-byte modification_time
                if (payloadEnd - payloadStart < 20) return false
                writeUint64(io, payloadStart + 4, seconds1904)
                writeUint64(io, payloadStart + 12, seconds1904)
                true
            }
            else -> false
        }
    }

    private fun readUint32(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
                (buffer[offset + 3].toLong() and 0xFF)
    }

    private fun readUint64(buffer: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return result
    }

    private fun writeUint32(io: RandomAccessIO, offset: Long, value: Long) {
        val buf = ByteArray(4)
        buf[0] = ((value shr 24) and 0xFF).toByte()
        buf[1] = ((value shr 16) and 0xFF).toByte()
        buf[2] = ((value shr 8) and 0xFF).toByte()
        buf[3] = (value and 0xFF).toByte()
        io.write(offset, buf, 4)
    }

    private fun writeUint64(io: RandomAccessIO, offset: Long, value: Long) {
        val buf = ByteArray(8)
        for (i in 7 downTo 0) {
            buf[i] = ((value shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        io.write(offset, buf, 8)
    }
}
