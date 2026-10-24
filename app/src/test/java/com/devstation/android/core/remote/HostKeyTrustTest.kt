package com.devstation.android.core.remote

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyTrustTest {

    @Test
    fun `fingerprint uses OpenSSH SHA256 format`() {
        val blob = byteArrayOf(0, 0, 0, 7) + "ssh-rsa".toByteArray() + byteArrayOf(1, 2, 3, 4, 5)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(blob)
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(expectedDigest)
        assertEquals(expected, HostKeyFingerprints.sha256Fingerprint(blob))
    }

    @Test
    fun `base64 encoder matches known vectors`() {
        assertEquals("Zg", HostKeyFingerprints.base64NoPadding("f".toByteArray()))
        assertEquals("Zm8", HostKeyFingerprints.base64NoPadding("fo".toByteArray()))
        assertEquals("Zm9v", HostKeyFingerprints.base64NoPadding("foo".toByteArray()))
        assertEquals("Zm9vYg", HostKeyFingerprints.base64NoPadding("foob".toByteArray()))
        assertEquals("+/8", HostKeyFingerprints.base64NoPadding(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertEquals("", HostKeyFingerprints.base64NoPadding(ByteArray(0)))
    }

    @Test
    fun `trust state follows TOFU rules`() {
        val fp = "SHA256:abc123"
        assertEquals(HostKeyTrustState.UNKNOWN, hostKeyTrustState(null, fp))
        assertEquals(HostKeyTrustState.UNKNOWN, hostKeyTrustState("", fp))
        assertEquals(HostKeyTrustState.UNKNOWN, hostKeyTrustState("  ", fp))
        assertEquals(HostKeyTrustState.TRUSTED, hostKeyTrustState(fp, fp))
        assertEquals(HostKeyTrustState.CHANGED, hostKeyTrustState(fp, "SHA256:other"))
    }

    @Test
    fun `matches tolerates surrounding whitespace but not different keys`() {
        assertTrue(HostKeyFingerprints.matches(" SHA256:abc ", "SHA256:abc"))
        assertFalse(HostKeyFingerprints.matches(null, "SHA256:abc"))
        assertFalse(HostKeyFingerprints.matches("SHA256:abc", "SHA256:abd"))
    }
}
