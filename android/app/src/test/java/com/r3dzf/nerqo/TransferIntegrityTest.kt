package com.r3dzf.nerqo

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class TransferIntegrityTest {
    private val original = "Verified folder member\n".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun acceptsExactSavedMemberIncludingEmptyFiles() {
        assertTrue(TransferIntegrity.matches(ByteArrayInputStream(original), original.size.toLong(), hash))
        assertTrue(TransferIntegrity.matches(ByteArrayInputStream(byteArrayOf()), 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    }
    @Test fun rejectsSameLengthMutationBeforeSkippingSavedMember() {
        val changed = original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(TransferIntegrity.matches(ByteArrayInputStream(changed), original.size.toLong(), hash))
    }
    @Test fun rejectsTruncatedOversizedAndInvalidMetadata() {
        assertFalse(TransferIntegrity.matches(ByteArrayInputStream(original.dropLast(1).toByteArray()), original.size.toLong(), hash))
        assertFalse(TransferIntegrity.matches(ByteArrayInputStream(original + 1.toByte()), original.size.toLong(), hash))
        assertFalse(TransferIntegrity.matches(ByteArrayInputStream(original), -1, hash))
        assertFalse(TransferIntegrity.matches(ByteArrayInputStream(original), original.size.toLong(), "bad hash"))
    }
}
