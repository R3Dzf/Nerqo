package com.r3dzf.nerqo

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest

internal interface DownloadTarget : Closeable {
    val resumable: Boolean
    fun length(): Long
    fun read(): InputStream
    /** Truncate to the committed prefix, then write at that offset. */
    fun writeAt(offset: Long): OutputStream
}
internal data class DownloadResponse(
    val status: Int, val contentRange: String?, val body: InputStream,
    private val release: () -> Unit = {}
) : Closeable {
    override fun close() { try { body.close() } finally { release() } }
}
internal class TransferIntegrityFailure(message: String) : IOException(message)

/** HTTP-range resume, independent of Android document providers for JVM testing. */
internal object ResumableDownload {
    private const val CHECKPOINT_BYTES = 4L * 1024 * 1024
    fun copy(target: DownloadTarget, size: Long, sha256: String, committed: Long,
             remote: (Long) -> DownloadResponse, checkpoint: (Long) -> Unit,
             progress: (Long, Long) -> Unit, control: TransferControl) {
        require(size in 0..(2L * 1024 * 1024 * 1024 * 1024))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        var offset = if (target.resumable && committed in 0..size && target.length() >= committed) committed else 0L
        var digest = MessageDigest.getInstance("SHA-256")
        fun hashPrefix() {
            if (offset == 0L) return
            control.phase("Verifying the saved download before resuming…")
            target.read().use { input -> control.resource(input) {
                val block = ByteArray(256 * 1024)
                var count = 0L
                while (count < offset) {
                    control.checkpoint()
                    val n = input.read(block, 0, minOf(block.size.toLong(), offset - count).toInt())
                    if (n <= 0) throw IOException("Saved download prefix is incomplete")
                    digest.update(block, 0, n); count += n
                }
            } }
        }
        fun verify() {
            val expected = sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (!MessageDigest.isEqual(digest.digest(), expected))
                throw TransferIntegrityFailure("File checksum changed. Retry to download a clean copy.")
        }
        try {
            hashPrefix()
            control.checkpoint()
            if (offset == size) {
                target.writeAt(offset).use { it.flush() }
                verify(); checkpoint(size); progress(size, size); return
            }
            remote(offset).use { response ->
                control.checkpoint()
                when (response.status) {
                    206 -> {
                        val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.contentRange ?: "")
                            ?: throw IOException("Invalid download range response")
                        val (start, end, total) = range.destructured
                        if (start.toLongOrNull() != offset || total.toLongOrNull() != size ||
                            end.toLongOrNull() != size - 1) throw IOException("Download range did not match the saved offset")
                    }
                    200 -> if (offset != 0L) {
                        // A server ignoring Range must never be appended to saved bytes.
                        offset = 0L; digest = MessageDigest.getInstance("SHA-256"); checkpoint(0)
                    }
                    else -> throw IOException("Download unavailable: HTTP ${response.status}")
                }
                target.writeAt(offset).use { output -> control.resource(output) {
                    var lastCheckpoint = offset
                    val block = ByteArray(256 * 1024)
                    progress(offset, size)
                    while (true) {
                        control.checkpoint()
                        val n = response.body.read(block)
                        if (n < 0) break
                        if (n == 0) continue
                        if (n.toLong() > size - offset) throw TransferIntegrityFailure("Received more bytes than expected")
                        output.write(block, 0, n); digest.update(block, 0, n); offset += n
                        if (offset - lastCheckpoint >= CHECKPOINT_BYTES) {
                            output.flush(); checkpoint(offset); lastCheckpoint = offset
                        }
                        progress(offset, size)
                    }
                    output.flush()
                    if (offset != size) throw IOException("Connection ended before the file was complete")
                    verify()
                    checkpoint(size)
                } }
            }
        } catch (failure: TransferIntegrityFailure) {
            // Only the caller-owned partial document is reset; it is never published.
            target.writeAt(0).use { it.flush() }; checkpoint(0)
            throw failure
        }
    }
}
