package com.example

import com.example.metadata.ByteArrayRandomAccessIO
import com.example.metadata.Mp4CreationTimePatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp4CreationTimePatcherTest {

    private fun writeUint32(baos: ByteArrayOutputStream, value: Long) {
        baos.write(((value shr 24) and 0xFF).toInt())
        baos.write(((value shr 16) and 0xFF).toInt())
        baos.write(((value shr 8) and 0xFF).toInt())
        baos.write((value and 0xFF).toInt())
    }

    private fun writeUint64(baos: ByteArrayOutputStream, value: Long) {
        for (i in 7 downTo 0) {
            baos.write(((value shr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeBox(type: String, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val totalSize = 8L + payload.size
        writeUint32(baos, totalSize)
        baos.write(type.toByteArray(Charsets.US_ASCII))
        baos.write(payload)
        return baos.toByteArray()
    }

    @Test
    fun testSyntheticMp4BoxWalkerVersion0() {
        // Build synthetic MP4 with mvhd (version 0), trak -> tkhd (v0), trak -> mdia -> mdhd (v0)

        // 1. mvhd payload (v0: 1 byte ver, 3 bytes flags, 4 creation, 4 modification, 4 timescale, 4 duration)
        val mvhdPayload = ByteArrayOutputStream().apply {
            write(0) // version 0
            write(byteArrayOf(0, 0, 0)) // flags
            writeUint32(this, 1000L) // initial creation_time
            writeUint32(this, 1000L) // initial modification_time
            writeUint32(this, 600L) // timescale
            writeUint32(this, 1200L) // duration
            write(ByteArray(80)) // remaining fields
        }.toByteArray()
        val mvhdBox = writeBox("mvhd", mvhdPayload)

        // 2. mdhd payload (v0)
        val mdhdPayload = ByteArrayOutputStream().apply {
            write(0) // version 0
            write(byteArrayOf(0, 0, 0)) // flags
            writeUint32(this, 500L) // creation_time
            writeUint32(this, 500L) // modification_time
            writeUint32(this, 600L)
            writeUint32(this, 1200L)
            write(ByteArray(4))
        }.toByteArray()
        val mdhdBox = writeBox("mdhd", mdhdPayload)

        // 3. mdia container box
        val mdiaBox = writeBox("mdia", mdhdBox)

        // 4. tkhd payload (v0)
        val tkhdPayload = ByteArrayOutputStream().apply {
            write(0) // version 0
            write(byteArrayOf(0, 0, 0)) // flags
            writeUint32(this, 200L) // creation_time
            writeUint32(this, 200L) // modification_time
            write(ByteArray(72))
        }.toByteArray()
        val tkhdBox = writeBox("tkhd", tkhdPayload)

        // 5. trak container containing tkhd and mdia
        val trakPayload = ByteArrayOutputStream().apply {
            write(tkhdBox)
            write(mdiaBox)
        }.toByteArray()
        val trakBox = writeBox("trak", trakPayload)

        // 6. moov container containing mvhd and trak
        val moovPayload = ByteArrayOutputStream().apply {
            write(mvhdBox)
            write(trakBox)
        }.toByteArray()
        val moovBox = writeBox("moov", moovPayload)

        // 7. Complete synthetic MP4 file with ftyp + moov
        val ftypBox = writeBox("ftyp", "isom\u0000\u0000\u0002\u0000isommp41".toByteArray(Charsets.ISO_8859_1))
        val fullMp4Bytes = ByteArrayOutputStream().apply {
            write(ftypBox)
            write(moovBox)
        }.toByteArray()

        val initialLength = fullMp4Bytes.size
        val io = ByteArrayRandomAccessIO(fullMp4Bytes)

        // Target test timestamp: 2024-01-01 00:00:00 UTC = 1,704,067,200 seconds
        val testEpochMillis = 1_704_067_200_000L
        val expected1904Seconds = (testEpochMillis / 1000L) + Mp4CreationTimePatcher.SECONDS_1904_TO_1970

        val patchedCount = Mp4CreationTimePatcher.patch(io, testEpochMillis)

        // Expect 3 patched boxes: mvhd, tkhd, mdhd
        assertEquals(3, patchedCount)
        // File length MUST NOT change
        assertEquals(initialLength.toLong(), io.length())

        // Read patched mvhd creation_time back from the byte array
        // ftyp size is 8 + 16 = 24 bytes
        // moov header is 8 bytes
        // mvhd header is 8 bytes
        // mvhd payload starts at 24 + 8 + 8 = 40
        // version (1 byte) + flags (3 bytes) = 4 bytes
        // creation_time is at offset 44
        val readBuf = ByteArray(4)
        io.read(44, readBuf, 4)
        val readCreationSeconds = ((readBuf[0].toLong() and 0xFF) shl 24) or
                ((readBuf[1].toLong() and 0xFF) shl 16) or
                ((readBuf[2].toLong() and 0xFF) shl 8) or
                (readBuf[3].toLong() and 0xFF)

        assertEquals(expected1904Seconds, readCreationSeconds)
    }

    @Test
    fun testSyntheticMp4BoxWalkerVersion1() {
        // mvhd version 1 (8-byte 64-bit creation and modification times)
        val mvhdPayload = ByteArrayOutputStream().apply {
            write(1) // version 1
            write(byteArrayOf(0, 0, 0)) // flags
            writeUint64(this, 1000L) // 8-byte creation_time
            writeUint64(this, 1000L) // 8-byte modification_time
            writeUint32(this, 600L) // timescale
            writeUint64(this, 1200L) // 8-byte duration
            write(ByteArray(80))
        }.toByteArray()
        val mvhdBox = writeBox("mvhd", mvhdPayload)
        val moovBox = writeBox("moov", mvhdBox)

        val io = ByteArrayRandomAccessIO(moovBox)
        val testEpochMillis = 1_704_067_200_000L
        val expected1904Seconds = (testEpochMillis / 1000L) + Mp4CreationTimePatcher.SECONDS_1904_TO_1970

        val patchedCount = Mp4CreationTimePatcher.patch(io, testEpochMillis)
        assertEquals(1, patchedCount)

        // Read 8-byte creation_time back (moov header 8 + mvhd header 8 + ver/flags 4 = 20)
        val readBuf = ByteArray(8)
        io.read(20, readBuf, 8)
        var readSec64 = 0L
        for (b in readBuf) {
            readSec64 = (readSec64 shl 8) or (b.toLong() and 0xFF)
        }
        assertEquals(expected1904Seconds, readSec64)
    }
}
