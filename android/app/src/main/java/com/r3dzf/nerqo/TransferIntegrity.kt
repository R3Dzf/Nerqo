package com.r3dzf.nerqo

import java.io.InputStream
import java.security.MessageDigest

/** Checks saved content before allowing a folder retry to skip it. */
internal object TransferIntegrity {
    fun matches(input: InputStream, expectedSize: Long, expectedSha: String): Boolean {
        if (expectedSize < 0 || !expectedSha.matches(Regex("[0-9a-f]{64}"))) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            size += count
            if (size > expectedSize) return false
            digest.update(buffer, 0, count)
        }
        return size == expectedSize && MessageDigest.isEqual(digest.digest(), expectedSha.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
}
