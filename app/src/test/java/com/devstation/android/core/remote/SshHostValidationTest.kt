package com.devstation.android.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHostValidationTest {

    @Test
    fun `valid input has no problems`() {
        assertTrue(validateSshHostInput("My VPS", "203.0.113.10", 22, "ubuntu").isEmpty())
        assertTrue(validateSshHostInput("Home", "server.example.com", 2222, "dev").isEmpty())
    }

    @Test
    fun `blank fields are reported`() {
        val problems = validateSshHostInput("", "", 22, "")
        assertEquals(3, problems.size)
    }

    @Test
    fun `port must be in range`() {
        assertEquals(1, validateSshHostInput("n", "h", 0, "u").size)
        assertEquals(1, validateSshHostInput("n", "h", 65536, "u").size)
        assertTrue(validateSshHostInput("n", "h", 1, "u").isEmpty())
        assertTrue(validateSshHostInput("n", "h", 65535, "u").isEmpty())
    }

    @Test
    fun `whitespace inside host or username is rejected`() {
        assertEquals(1, validateSshHostInput("n", "my host", 22, "u").size)
        assertEquals(1, validateSshHostInput("n", "h", 22, "user name").size)
    }
}
