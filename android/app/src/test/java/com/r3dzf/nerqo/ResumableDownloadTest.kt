package com.r3dzf.nerqo

import java.io.*
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ResumableDownloadTest {
    private val data = ByteArray(9 * 1024 * 1024 + 123) { (it * 31).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private class Target(val file: File, override val resumable: Boolean = true) : DownloadTarget {
        override fun length() = file.length()
        override fun read(): InputStream = file.inputStream()
        override fun writeAt(offset: Long): OutputStream {
            val random = RandomAccessFile(file, "rw").apply { setLength(offset); seek(offset) }
            return object : OutputStream() {
                override fun write(b: Int) { random.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { random.write(b, off, len) }
                override fun close() { random.close() }
            }
        }
        override fun close() { file.delete() }
    }
    private fun target() = Target(File.createTempFile("nerqo-download", ".part"))
    private fun remote(offset: Long, bytes: ByteArray = data, ignoreRange: Boolean = false): DownloadResponse {
        val start = if (ignoreRange) 0 else offset.toInt()
        return DownloadResponse(if (start == 0) 200 else 206,
            if (start == 0) null else "bytes $start-${bytes.size - 1}/${bytes.size}",
            ByteArrayInputStream(bytes, start, bytes.size - start))
    }
    @Test fun pauseThenNewControllerResumesAtCommittedByteWithoutDuplication() {
        target().use { target ->
            val control = TransferControl()
            var committed = 0L
            assertThrows(TransferPaused::class.java) {
                ResumableDownload.copy(target, data.size.toLong(), hash(data), 0, { remote(it) },
                    { committed = it }, { done, _ -> if (done >= 5 * 1024 * 1024) control.interrupt(TransferStop.PAUSE) }, control)
            }
            assertEquals(4L * 1024 * 1024, committed)
            var requested = -1L
            ResumableDownload.copy(target, data.size.toLong(), hash(data), committed,
                { requested = it; remote(it) }, { committed = it }, { _, _ -> }, TransferControl())
            assertEquals(4L * 1024 * 1024, requested)
            assertArrayEquals(data, target.file.readBytes())
            assertEquals(data.size.toLong(), committed)
        }
    }
    @Test fun ignoresRangeBySafelyRestartingInsteadOfAppending() {
        target().use { target ->
            target.file.writeBytes(data.copyOf(1024 * 1024))
            ResumableDownload.copy(target, data.size.toLong(), hash(data), target.length(),
                { remote(it, ignoreRange = true) }, {}, { _, _ -> }, TransferControl())
            assertArrayEquals(data, target.file.readBytes())
        }
    }
    @Test fun alteredSavedPrefixFailsHashAndResetsOnlyPartial() {
        target().use { target ->
            val prefix = data.copyOf(1024 * 1024).also { it[0] = (it[0].toInt() xor 1).toByte() }
            target.file.writeBytes(prefix)
            var committed = target.length()
            assertThrows(TransferIntegrityFailure::class.java) {
                ResumableDownload.copy(target, data.size.toLong(), hash(data), committed,
                    { remote(it) }, { committed = it }, { _, _ -> }, TransferControl())
            }
            assertEquals(0L, target.length())
            assertEquals(0L, committed)
        }
    }
    @Test fun rejectsMalformedRangeBeforeModifyingSavedPrefix() {
        target().use { target ->
            val prefix = data.copyOf(1024)
            target.file.writeBytes(prefix)
            assertThrows(IOException::class.java) {
                ResumableDownload.copy(target, data.size.toLong(), hash(data), target.length(),
                    { DownloadResponse(206, "bytes 0-100/101", ByteArrayInputStream(byteArrayOf())) }, {}, { _, _ -> }, TransferControl())
            }
            assertArrayEquals(prefix, target.file.readBytes())
        }
    }
    @Test fun nonSeekableProviderRestartsAtZeroInsteadOfAppending() {
        Target(File.createTempFile("nerqo-pipe", ".part"), resumable = false).use { target ->
            target.file.writeBytes(data.copyOf(1024))
            var offset = -1L
            ResumableDownload.copy(target, data.size.toLong(), hash(data), 1024,
                { offset = it; remote(it) }, {}, { _, _ -> }, TransferControl())
            assertEquals(0L, offset)
            assertArrayEquals(data, target.file.readBytes())
        }
    }
    @Test fun zeroLengthDownloadTruncatesOwnedDestination() {
        target().use { target ->
            target.file.writeText("old partial data")
            ResumableDownload.copy(target, 0, hash(byteArrayOf()), 0,
                { fail("An empty verified file needs no range request"); remote(0) }, {}, { _, _ -> }, TransferControl())
            assertEquals(0L, target.length())
        }
    }
    @Test fun truncatedNetworkResponseKeepsLastCommittedCheckpoint() {
        target().use { target ->
            var committed = 0L
            assertThrows(IOException::class.java) {
                ResumableDownload.copy(target, data.size.toLong(), hash(data), 0,
                    { DownloadResponse(200, null, ByteArrayInputStream(data.copyOf(6 * 1024 * 1024))) },
                    { committed = it }, { _, _ -> }, TransferControl())
            }
            assertEquals(4L * 1024 * 1024, committed)
            ResumableDownload.copy(target, data.size.toLong(), hash(data), committed,
                { remote(it) }, { committed = it }, { _, _ -> }, TransferControl())
            assertArrayEquals(data, target.file.readBytes())
        }
    }
}
