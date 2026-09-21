package com.devstation.android.core.agent

import com.devstation.android.core.ai.AIToolParameter
import com.devstation.android.core.ai.AIToolParameterType
import com.devstation.android.core.ai.AIToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun `redacts bearer tokens and api keys`() {
        val text = "Authorization: Bearer sk-abcdef1234567890\nkey=AIzaSyA1234567890abcdefghij"
        val redacted = SecretRedactor.redact(text)
        assertFalse(redacted.contains("sk-abcdef1234567890"))
        assertFalse(redacted.contains("AIzaSyA1234567890abcdefghij"))
        assertTrue(redacted.contains(SecretRedactor.MASK))
    }

    @Test
    fun `keeps the key name but masks the value`() {
        val redacted = SecretRedactor.redact("api_key=supersecretvalue")
        assertTrue(redacted.startsWith("api_key="))
        assertFalse(redacted.contains("supersecretvalue"))
    }

    @Test
    fun `redacts private key blocks`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----"
        assertEquals(SecretRedactor.MASK, SecretRedactor.redact(pem))
    }

    @Test
    fun `leaves ordinary output untouched`() {
        val text = "tests: 24 passed\nbuild succeeded"
        assertEquals(text, SecretRedactor.redact(text))
    }
}

class OutputLimiterTest {

    @Test
    fun `returns short text unchanged`() {
        assertEquals("short", OutputLimiter.truncate("short", 100))
    }

    @Test
    fun `truncates with an explicit marker and keeps both ends`() {
        val text = "HEAD-" + "x".repeat(5000) + "-TAIL"
        val bounded = OutputLimiter.truncate(text, 400)
        assertTrue(bounded.length <= 400)
        assertTrue(bounded.contains("HEAD-"))
        assertTrue(bounded.contains("-TAIL"))
        assertTrue(bounded.contains("characters omitted"))
    }

    @Test
    fun `line truncation reports how many lines were dropped`() {
        val lines = (1..50).map { "line $it" }
        val kept = OutputLimiter.truncateLines(lines, 10)
        assertEquals(11, kept.size)
        assertTrue(kept.last().contains("40 characters omitted") || kept.last().contains("omitted"))
    }
}

class ToolArgumentValidatorTest {

    private val definition = ToolDefinition(
        name = "write_file",
        description = "write",
        parameters = listOf(
            AIToolParameter("path", AIToolParameterType.STRING, "path"),
            AIToolParameter("content", AIToolParameterType.STRING, "content", required = false),
            AIToolParameter("line", AIToolParameterType.INTEGER, "line", required = false)
        )
    )

    @Test
    fun `accepts valid arguments`() {
        val args = buildJsonObject {
            put("path", "a.txt")
            put("line", 3)
        }
        assertTrue(ToolArgumentValidator.validate(definition, args) is ArgumentValidation.Valid)
    }

    @Test
    fun `rejects unknown parameters`() {
        val args = buildJsonObject { put("path", "a.txt"); put("evil", "x") }
        val result = ToolArgumentValidator.validate(definition, args)
        assertTrue(result is ArgumentValidation.Invalid)
        assertTrue((result as ArgumentValidation.Invalid).message.contains("Unknown parameter"))
    }

    @Test
    fun `rejects missing required parameters`() {
        val result = ToolArgumentValidator.validate(definition, buildJsonObject { })
        assertTrue(result is ArgumentValidation.Invalid)
    }

    @Test
    fun `rejects wrong types`() {
        val args = buildJsonObject { put("path", "a.txt"); put("line", "not-a-number") }
        assertTrue(ToolArgumentValidator.validate(definition, args) is ArgumentValidation.Invalid)
    }

    @Test
    fun `rejects oversized string parameters`() {
        val args = buildJsonObject {
            put("path", "a.txt")
            put("content", "x".repeat(ToolArgumentValidator.MAX_STRING_LENGTH + 10))
        }
        assertTrue(ToolArgumentValidator.validate(definition, args) is ArgumentValidation.Invalid)
    }
}

class ToolRegistryTest {

    private val readTool = FakeTool("read_file")

    @Test
    fun `registers and resolves tools`() {
        val registry = ToolRegistry(listOf(readTool))
        assertEquals(1, registry.size)
        assertNotNull(registry.get("read_file"))
        assertNull(registry.get("rm_rf"))
        assertEquals(listOf("read_file"), registry.names)
    }

    @Test
    fun `exposes provider-neutral specs`() {
        val registry = ToolRegistry(listOf(readTool))
        val spec: AIToolSpec = registry.specs().single()
        assertEquals("read_file", spec.name)
        assertEquals("path", spec.parameters.single().name)
    }

    @Test
    fun `rejects duplicate and malformed names`() {
        val registry = ToolRegistry()
        registry.register(readTool)
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeTool("read_file")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeTool("bad name")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeTool("")) }
    }
}

class CommandClassifierTest {

    @Test
    fun `read only commands are low risk and auto-allowed`() {
        listOf("ls -la", "cat README.md", "grep -n foo src/App.kt", "git status").forEach { command ->
            val classification = CommandClassifier.classify(command)
            assertEquals(command, CommandCategory.READ_ONLY, classification.category)
            assertEquals(ToolPermission.ALLOW, classification.defaultPermission())
        }
    }

    @Test
    fun `project modification requires approval`() {
        val classification = CommandClassifier.classify("mkdir build")
        assertEquals(CommandCategory.MODIFY_PROJECT, classification.category)
        assertEquals(ToolPermission.ASK, classification.defaultPermission())
    }

    @Test
    fun `package installation requires approval`() {
        assertEquals(CommandCategory.INSTALL_PACKAGE, CommandClassifier.classify("npm install").category)
        assertEquals(CommandCategory.INSTALL_PACKAGE, CommandClassifier.classify("apk add nodejs").category)
        assertEquals(CommandCategory.INSTALL_PACKAGE, CommandClassifier.classify("pip install requests").category)
    }

    @Test
    fun `network commands require approval`() {
        assertEquals(CommandCategory.NETWORK, CommandClassifier.classify("curl https://example.com").category)
        assertEquals(CommandCategory.NETWORK, CommandClassifier.classify("git clone https://example.com/x.git").category)
    }

    @Test
    fun `destructive commands always require approval`() {
        listOf("rm -rf build", "rm file.txt", "git reset --hard", "git clean -fd", "apk del nodejs")
            .forEach { command ->
                val classification = CommandClassifier.classify(command)
                assertEquals(command, CommandCategory.DESTRUCTIVE, classification.category)
                assertEquals(ToolPermission.ALWAYS_ASK, classification.defaultPermission())
                assertEquals(ToolRiskLevel.CRITICAL, classification.riskLevel)
            }
    }

    @Test
    fun `compound commands take the most dangerous segment`() {
        val classification = CommandClassifier.classify("ls && rm -rf /workspace")
        assertEquals(CommandCategory.DESTRUCTIVE, classification.category)
        assertTrue(classification.compound)
    }

    @Test
    fun `unknown commands require approval rather than being allowed`() {
        val classification = CommandClassifier.classify("frobnicate --deep")
        assertEquals(CommandCategory.MODIFY_PROJECT, classification.category)
        assertEquals(ToolPermission.ASK, classification.defaultPermission())
    }

    @Test
    fun `sudo prefix does not downgrade risk`() {
        assertEquals(CommandCategory.DESTRUCTIVE, CommandClassifier.classify("sudo rm -rf /").category)
    }
}
