package com.devstation.android.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsUrlValidatorTest {

    @Test
    fun `accepts https urls`() {
        val url = HttpsUrlValidator.validate("https://api.openai.com/v1/chat/completions")
        assertEquals("api.openai.com", url.host)
        assertTrue(url.isHttps)
    }

    @Test
    fun `rejects plaintext http`() {
        try {
            HttpsUrlValidator.validate("http://api.openai.com/v1")
            org.junit.Assert.fail("Expected SecurityException")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test
    fun `rejects invalid urls`() {
        try {
            HttpsUrlValidator.validate("not a url")
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects even loopback http when flag is disabled`() {
        try {
            HttpsUrlValidator.validate("http://localhost:8080", allowInsecureLocalHost = false)
            org.junit.Assert.fail("Expected SecurityException")
        } catch (e: SecurityException) {
            // expected
        }
    }

    @Test
    fun `allows loopback http only with explicit flag`() {
        val url = HttpsUrlValidator.validate("http://127.0.0.1:8080/v1", allowInsecureLocalHost = true)
        assertEquals("127.0.0.1", url.host)
    }
}
