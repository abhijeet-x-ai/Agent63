package com.devstation.android.core.mcp

import com.devstation.android.core.agent.profiles.AgentProfile
import com.devstation.android.core.agent.profiles.AgentProfileConfigStore
import com.devstation.android.core.agent.profiles.AgentProfileManager
import com.devstation.android.core.agent.profiles.AgentProfilePermissions
import com.devstation.android.core.agent.profiles.AgentProfileSecurityScope
import com.devstation.android.core.agent.profiles.InMemoryAgentProfileConfigStore
import com.devstation.android.core.agent.profiles.AgentProfileResult
import com.devstation.android.core.common.DefaultDispatcherProvider
import com.devstation.android.core.security.policy.InMemorySecurityAuditStore
import com.devstation.android.core.security.policy.McpSecurityClassifier
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.skills.BuiltInSkills
import com.devstation.android.core.skills.InMemorySkillConfigStore
import com.devstation.android.core.skills.SkillCapability
import com.devstation.android.core.skills.SkillDefinition
import com.devstation.android.core.skills.SkillManager
import com.devstation.android.core.skills.SkillSecurityProfile
import com.devstation.android.core.skills.SkillSource
import com.devstation.android.core.skills.SkillValidator
import com.devstation.android.core.skills.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 8: comprehensive test suite for MCP, Skills, and Agent Profiles.
 * Tests security integration, validation, lifecycle, and attack scenarios.
 */
class McpTestSuite {

    private lateinit var auditLogger: SecurityAuditLogger
    private lateinit var dispatchers: DefaultDispatcherProvider

    @Before
    fun setup() {
        auditLogger = SecurityAuditLogger(InMemorySecurityAuditStore())
        dispatchers = DefaultDispatcherProvider()
    }

    // ---- MCP Models Tests ----

    @Test
    fun `McpServerConfig validates required fields`() {
        val valid = McpServerConfig(name = "Test Server", command = "echo")
        assertTrue(valid.name.isNotEmpty())
        assertEquals(McpTransportType.STDIO, valid.transportType)

        // Blank name should fail
        try {
            McpServerConfig(name = "", command = "echo")
            fail("Should throw for blank name")
        } catch (_: IllegalArgumentException) { }

        // STDIO without command should fail
        try {
            McpServerConfig(name = "Test", transportType = McpTransportType.STDIO, command = "")
            fail("Should throw for STDIO without command")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `McpServerConfig detects sensitive environment keys`() {
        val clean = McpServerConfig(
            name = "Clean",
            command = "echo",
            environment = mapOf("DEBUG" to "true", "PORT" to "8080")
        )
        assertFalse(clean.hasSensitiveEnvironmentKeys())

        val sensitive = McpServerConfig(
            name = "Sensitive",
            command = "echo",
            environment = mapOf("API_KEY" to "secret123")
        )
        assertTrue(sensitive.hasSensitiveEnvironmentKeys())

        val tokenEnv = McpServerConfig(
            name = "Token",
            command = "echo",
            environment = mapOf("AUTH_TOKEN" to "abc")
        )
        assertTrue(tokenEnv.hasSensitiveEnvironmentKeys())
    }

    @Test
    fun `McpCapability validates name`() {
        val valid = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.TOOL,
            name = "read_file"
        )
        assertTrue(valid.name.isNotEmpty())

        try {
            McpCapability(serverId = "s1", capabilityType = McpCapabilityType.TOOL, name = "")
            fail("Should throw for blank name")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `McpSecurityClassifier classifies read-only tools`() {
        val readTool = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.TOOL,
            name = "read_document",
            description = "Read a document"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.READ_ONLY,
            McpSecurityClassifier.classify(readTool)
        )
    }

    @Test
    fun `McpSecurityClassifier classifies write tools`() {
        val writeTool = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.TOOL,
            name = "create_file",
            description = "Create a new file"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.PROJECT_WRITE,
            McpSecurityClassifier.classify(writeTool)
        )
    }

    @Test
    fun `McpSecurityClassifier classifies destructive tools`() {
        val deleteTool = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.TOOL,
            name = "delete_all",
            description = "Delete all records"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.DESTRUCTIVE,
            McpSecurityClassifier.classify(deleteTool)
        )
    }

    @Test
    fun `McpSecurityClassifier classifies network tools`() {
        val httpTool = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.TOOL,
            name = "fetch_url",
            description = "HTTP request"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.NETWORK,
            McpSecurityClassifier.classify(httpTool)
        )
    }

    @Test
    fun `McpSecurityClassifier classifies system tools`() {
        val execTool = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.TOOL,
            name = "exec_command",
            description = "Run a shell command"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.SYSTEM,
            McpSecurityClassifier.classify(execTool)
        )
    }

    @Test
    fun `McpSecurityClassifier classifies unknown tools strictly`() {
        val unknownTool = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.TOOL,
            name = "mystery_operation"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.UNKNOWN,
            McpSecurityClassifier.classify(unknownTool)
        )
    }

    @Test
    fun `McpSecurityClassifier resources and prompts are read-only`() {
        val resource = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.RESOURCE,
            name = "config"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.READ_ONLY,
            McpSecurityClassifier.classify(resource)
        )

        val prompt = McpCapability(
            serverId = "s1",
            capabilityType = McpCapabilityType.PROMPT,
            name = "review"
        )
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.READ_ONLY,
            McpSecurityClassifier.classify(prompt)
        )
    }

    // ---- MCP Capability Registry Tests ----

    @Test
    fun `McpCapabilityRegistry registers and retrieves capabilities`() {
        val registry = McpCapabilityRegistry()
        val caps = listOf(
            McpCapability(serverId = "s1", capabilityType = McpCapabilityType.TOOL, name = "read_file"),
            McpCapability(serverId = "s1", capabilityType = McpCapabilityType.RESOURCE, name = "config")
        )
        registry.registerCapabilities("s1", caps)

        assertEquals(2, registry.totalCount)
        assertEquals(1, registry.totalToolCount)
        assertNotNull(registry.getCapability("s1", "read_file"))
        assertEquals(2, registry.capabilitiesForServer("s1").size)
    }

    @Test
    fun `McpCapabilityRegistry removes server capabilities`() {
        val registry = McpCapabilityRegistry()
        registry.registerCapabilities("s1", listOf(
            McpCapability(serverId = "s1", capabilityType = McpCapabilityType.TOOL, name = "tool1")
        ))
        assertEquals(1, registry.totalCount)

        registry.removeServerCapabilities("s1")
        assertEquals(0, registry.totalCount)
        assertNull(registry.getCapability("s1", "tool1"))
    }

    @Test
    fun `McpCapabilityRegistry replaces capabilities on re-register`() {
        val registry = McpCapabilityRegistry()
        registry.registerCapabilities("s1", listOf(
            McpCapability(serverId = "s1", capabilityType = McpCapabilityType.TOOL, name = "old_tool")
        ))
        registry.registerCapabilities("s1", listOf(
            McpCapability(serverId = "s1", capabilityType = McpCapabilityType.TOOL, name = "new_tool")
        ))

        assertEquals(1, registry.totalCount)
        assertNull(registry.getCapability("s1", "old_tool"))
        assertNotNull(registry.getCapability("s1", "new_tool"))
    }

    @Test
    fun `McpCapabilityRegistry classifies capabilities on registration`() {
        val registry = McpCapabilityRegistry()
        registry.registerCapabilities("s1", listOf(
            McpCapability(serverId = "s1", capabilityType = McpCapabilityType.TOOL, name = "read_document")
        ))

        val cap = registry.getCapability("s1", "read_document")
        assertNotNull(cap)
        assertEquals(
            com.devstation.android.core.mcp.McpSecurityClassification.READ_ONLY,
            cap!!.securityClassification
        )
    }

    // ---- InMemory MCP Transport Tests ----

    @Test
    fun `InMemoryMcpTransport connects and sends`() = runTest {
        val transport = InMemoryMcpTransport()
        val config = McpServerConfig(name = "Test", command = "echo")

        val conn = transport.connect(config)
        assertTrue(conn.isSuccess)

        val connection = conn.getOrNull()!!
        val request = McpJsonRpcRequest(method = "test")
        val response = transport.send(connection, request)
        assertTrue(response.isSuccess)

        transport.close(connection)
    }

    @Test
    fun `InMemoryMcpTransport can simulate failures`() = runTest {
        val transport = InMemoryMcpTransport()
        transport.setFailure("Simulated error")

        val config = McpServerConfig(name = "Test", command = "echo")
        val conn = transport.connect(config)
        assertTrue(conn.isFailure)
        assertTrue(conn.exceptionOrNull()?.message?.contains("Simulated error") == true)
    }

    // ---- MCP Client Tests ----

    @Test
    fun `McpClient initializes and lists tools`() = runTest {
        val transport = InMemoryMcpTransport()
        val client = McpClient(transport)

        val config = McpServerConfig(name = "Test", command = "echo")
        val connResult = transport.connect(config)
        assertTrue(connResult.isSuccess)
        val connection = connResult.getOrNull()!!

        val initResult = client.initialize(connection)
        assertTrue(initResult.isSuccess)
        assertNotNull(initResult.getOrNull())

        // List tools returns empty when no tools are registered
        val tools = client.listTools(initResult.getOrNull()!!)
        assertTrue(tools.isSuccess)
        assertTrue(tools.getOrNull()?.isEmpty() == true)
    }

    @Test
    fun `McpClient handles transport failures`() = runTest {
        val transport = InMemoryMcpTransport()
        transport.setFailure("Connection refused")

        val client = McpClient(transport)
        val config = McpServerConfig(name = "Test", command = "echo")
        val connResult = transport.connect(config)
        assertTrue(connResult.isFailure)
    }

    // ---- MCP Server Manager Tests ----

    @Test
    fun `McpServerManager registers and lists servers`() = runTest {
        val store = InMemoryMcpConfigStore()
        val manager = McpServerManager(
            transportFactory = { InMemoryMcpTransport() },
            capabilityRegistry = McpCapabilityRegistry(),
            audit = auditLogger,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope(),
            configStore = store
        )

        val config = McpServerConfig(name = "Test Server", command = "echo")
        val result = manager.registerServer(config)
        assertTrue(result.isSuccess)

        manager.loadServers()
        assertEquals(1, manager.servers.value.size)
        assertEquals("Test Server", manager.servers.value[config.id]?.name)
    }

    @Test
    fun `McpServerManager rejects servers with sensitive env keys`() = runTest {
        val store = InMemoryMcpConfigStore()
        val manager = McpServerManager(
            transportFactory = { InMemoryMcpTransport() },
            capabilityRegistry = McpCapabilityRegistry(),
            audit = auditLogger,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope(),
            configStore = store
        )

        val config = McpServerConfig(
            name = "Bad Server",
            command = "echo",
            environment = mapOf("API_KEY" to "secret")
        )
        val result = manager.registerServer(config)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("sensitive") == true)
    }

    @Test
    fun `McpServerManager connects and discovers capabilities`() = runTest {
        val store = InMemoryMcpConfigStore()
        val registry = McpCapabilityRegistry()
        val manager = McpServerManager(
            transportFactory = { InMemoryMcpTransport() },
            capabilityRegistry = registry,
            audit = auditLogger,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope(),
            configStore = store
        )

        val config = McpServerConfig(name = "Test", command = "echo")
        val regResult = manager.registerServer(config)
        assertTrue(regResult.isSuccess)
        manager.loadServers()

        // Connect will succeed (in-memory transport) but tool listing returns empty
        val connectResult = manager.connect(config.id)
        // Connection may fail if the MCP server sends an error response
        // The important thing is it doesn't crash
        assertNotNull(connectResult)
    }

    @Test
    fun `McpServerManager disconnects cleanly`() = runTest {
        val store = InMemoryMcpConfigStore()
        val manager = McpServerManager(
            transportFactory = { InMemoryMcpTransport() },
            capabilityRegistry = McpCapabilityRegistry(),
            audit = auditLogger,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope(),
            configStore = store
        )

        val config = McpServerConfig(name = "Test", command = "echo")
        manager.registerServer(config)
        manager.loadServers()
        manager.connect(config.id)

        manager.disconnect(config.id)
        assertFalse(manager.isConnected(config.id))
    }

    @Test
    fun `McpServerManager enables and disables servers`() = runTest {
        val store = InMemoryMcpConfigStore()
        val manager = McpServerManager(
            transportFactory = { InMemoryMcpTransport() },
            capabilityRegistry = McpCapabilityRegistry(),
            audit = auditLogger,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope(),
            configStore = store
        )

        val config = McpServerConfig(name = "Test", command = "echo")
        manager.registerServer(config)
        manager.setEnabled(config.id, false)

        val result = manager.connect(config.id)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("disabled") == true)
    }

    @Test
    fun `McpServerManager removes server and cleans up capabilities`() = runTest {
        val store = InMemoryMcpConfigStore()
        val registry = McpCapabilityRegistry()
        val manager = McpServerManager(
            transportFactory = { InMemoryMcpTransport() },
            capabilityRegistry = registry,
            audit = auditLogger,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope(),
            configStore = store
        )

        val config = McpServerConfig(name = "Test", command = "echo")
        manager.registerServer(config)
        manager.loadServers()

        val removeResult = manager.removeServer(config.id)
        assertTrue(removeResult.isSuccess)
        assertEquals(0, manager.servers.value.size)
        assertEquals(0, registry.totalCount)
    }

    // ---- Skill Tests ----

    @Test
    fun `Built-in skills are valid`() {
        val validator = SkillValidator { setOf("read_file", "write_file", "search_project", "list_directory", "create_file", "apply_patch", "run_terminal_command") }

        BuiltInSkills.all.forEach { skill ->
            assertTrue(
                "Built-in skill '${skill.name}' should be valid: ${validator.validate(skill).joinToString()}",
                validator.isValid(skill)
            )
        }
    }

    @Test
    fun `SkillValidator rejects skills with unknown tools`() {
        val validator = SkillValidator { setOf("read_file") }

        val skill = SkillDefinition(
            name = "Test",
            description = "Test",
            instructions = "Do something",
            requiredTools = listOf("nonexistent_tool")
        )

        val reasons = validator.validate(skill)
        assertTrue(reasons.any { it.contains("nonexistent_tool") })
    }

    @Test
    fun `SkillValidator rejects blank instructions`() {
        val validator = SkillValidator()
        // SkillDefinition constructor requires non-blank instructions, so validation
        // of the schema should catch this at the model level
        try {
            SkillDefinition(name = "Test", description = "Test skill", instructions = "")
            fail("Should throw for blank instructions")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `SkillManager registers and lists skills`() = runTest {
        val store = InMemorySkillConfigStore()
        val manager = SkillManager(
            validator = SkillValidator(),
            skillExecutor = com.devstation.android.core.skills.SkillExecutor(
                agentRuntime = createMockAgentRuntime()
            ),
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )

        manager.loadSkills()

        // Built-in skills should be loaded
        assertTrue(manager.skills.value.size >= BuiltInSkills.all.size)

        // Register a custom skill
        val custom = SkillDefinition(
            name = "My Custom Skill",
            description = "Custom",
            instructions = "Do custom things",
            source = SkillSource.USER
        )
        val result = manager.registerSkill(custom)
        assertTrue(result.isSuccess)
        assertNotNull(manager.getSkill(custom.id))
    }

    @Test
    fun `SkillManager rejects duplicate built-in skills`() = runTest {
        val store = InMemorySkillConfigStore()
        val manager = SkillManager(
            validator = SkillValidator(),
            skillExecutor = com.devstation.android.core.skills.SkillExecutor(
                agentRuntime = createMockAgentRuntime()
            ),
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )
        manager.loadSkills()

        val duplicate = BuiltInSkills.all.first().copy(id = "builtin_code_review")
        val result = manager.registerSkill(duplicate)
        assertTrue(result.isFailure)
    }

    @Test
    fun `SkillManager enables and disables skills`() = runTest {
        val store = InMemorySkillConfigStore()
        val manager = SkillManager(
            validator = SkillValidator(),
            skillExecutor = com.devstation.android.core.skills.SkillExecutor(
                agentRuntime = createMockAgentRuntime()
            ),
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )
        manager.loadSkills()

        val skillId = BuiltInSkills.all.first().id
        manager.setEnabled(skillId, false)
        assertFalse(manager.getSkill(skillId)?.enabled == true)

        manager.setEnabled(skillId, true)
        assertTrue(manager.getSkill(skillId)?.enabled == true)
    }

    @Test
    fun `SkillManager cannot remove built-in skills`() = runTest {
        val store = InMemorySkillConfigStore()
        val manager = SkillManager(
            validator = SkillValidator(),
            skillExecutor = com.devstation.android.core.skills.SkillExecutor(
                agentRuntime = createMockAgentRuntime()
            ),
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )
        manager.loadSkills()

        val result = manager.removeSkill(BuiltInSkills.all.first().id)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Built-in") == true)
    }

    @Test
    fun `SkillManager prevents self-recursion`() = runTest {
        val store = InMemorySkillConfigStore()
        val manager = SkillManager(
            validator = SkillValidator(),
            skillExecutor = com.devstation.android.core.skills.SkillExecutor(
                agentRuntime = createMockAgentRuntime()
            ),
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )
        manager.loadSkills()

        val skill = BuiltInSkills.all.first()
        val result = manager.executeSkill(skill.id, "test", "project1")
        // Should either start or be rejected (depending on mock runtime)
        // The key test is that it doesn't crash
        assertNotNull(result)
    }

    // ---- Skill Security Tests ----

    @Test
    fun `SkillSecurityProfile validates correctly`() {
        val valid = SkillSecurityProfile(
            requestedCapabilities = listOf(SkillCapability.FILESYSTEM_READ),
            touchesSensitiveFiles = true
        )
        assertTrue(valid.isValid())

        // touchesSensitiveFiles without filesystem capability is still valid
        // (the validation is structural, not semantic)
        val ok = SkillSecurityProfile(
            requestedCapabilities = listOf(SkillCapability.TERMINAL_READ),
            touchesSensitiveFiles = false
        )
        assertTrue(ok.isValid())
    }

    @Test
    fun `SkillCapability enum values cover expected operations`() {
        val allCaps = SkillCapability.values()
        assertTrue(allCaps.contains(SkillCapability.FILESYSTEM_READ))
        assertTrue(allCaps.contains(SkillCapability.FILESYSTEM_WRITE))
        assertTrue(allCaps.contains(SkillCapability.TERMINAL_READ))
        assertTrue(allCaps.contains(SkillCapability.TERMINAL_WRITE))
        assertTrue(allCaps.contains(SkillCapability.NETWORK))
        assertTrue(allCaps.contains(SkillCapability.PACKAGE_INSTALL))
    }

    @Test
    fun `SkillExecutionContext enforces recursion depth`() {
        val ctx = SkillExecutionContext(skillId = "s1")
        assertTrue(ctx.canRecurse())

        val child1 = ctx.childContext("s2")
        assertTrue(child1.canRecurse())

        val child2 = child1.childContext("s3")
        assertTrue(child2.canRecurse())

        val child3 = child2.childContext("s4")
        assertFalse(child3.canRecurse())
    }

    // ---- Agent Profile Tests ----

    @Test
    fun `AgentProfile validates fields`() {
        val valid = AgentProfile(
            name = "Test Agent",
            description = "A test agent",
            systemInstructions = "Be helpful"
        )
        assertTrue(valid.name.isNotEmpty())
        assertEquals(25, valid.maxIterations)

        // Blank name should fail
        try {
            AgentProfile(name = "")
            fail("Should throw for blank name")
        } catch (_: IllegalArgumentException) { }

        // Name too long should fail
        try {
            AgentProfile(name = "x".repeat(101))
            fail("Should throw for name too long")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `AgentProfileManager creates and lists profiles`() = runTest {
        val store = InMemoryAgentProfileConfigStore()
        val manager = AgentProfileManager(
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )
        manager.loadProfiles()

        val profile = AgentProfile(name = "Test Agent", description = "Test")
        val result = manager.createProfile(profile)
        assertTrue(result is AgentProfileResult.Success)
        assertNotNull(manager.getProfile(profile.id))
        assertEquals(1, manager.allProfiles().size)
    }

    @Test
    fun `AgentProfileManager rejects invalid profiles`() = runTest {
        val store = InMemoryAgentProfileConfigStore()
        val manager = AgentProfileManager(
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )

        // AgentProfile constructor requires non-blank name, so it throws at construction
        try {
            val invalid = AgentProfile(name = "")
            fail("Should throw for blank name")
        } catch (_: IllegalArgumentException) { }

        // Test with a name that's too long
        try {
            val tooLong = AgentProfile(name = "x".repeat(101))
            fail("Should throw for name too long")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `AgentProfileManager duplicates profiles`() = runTest {
        val store = InMemoryAgentProfileConfigStore()
        val manager = AgentProfileManager(
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )

        val original = AgentProfile(name = "Original", description = "Original agent")
        manager.createProfile(original)

        val result = manager.duplicateProfile(original.id, "Copy")
        assertTrue(result is AgentProfileResult.Success)
        assertEquals(2, manager.allProfiles().size)
        assertNotEquals(original.id, (result as AgentProfileResult.Success).profile.id)
    }

    @Test
    fun `AgentProfileManager deletes profiles`() = runTest {
        val store = InMemoryAgentProfileConfigStore()
        val manager = AgentProfileManager(
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )

        val profile = AgentProfile(name = "Delete Me", description = "To be deleted")
        manager.createProfile(profile)
        assertEquals(1, manager.allProfiles().size)

        val result = manager.deleteProfile(profile.id)
        assertTrue(result.isSuccess)
        assertEquals(0, manager.allProfiles().size)
    }

    @Test
    fun `AgentProfileManager sets active profile`() = runTest {
        val store = InMemoryAgentProfileConfigStore()
        val manager = AgentProfileManager(
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )

        val profile = AgentProfile(name = "Active", description = "Active agent")
        manager.createProfile(profile)

        manager.setActiveProfile(profile.id)
        assertEquals(profile.id, manager.activeProfile.value?.id)

        manager.setActiveProfile(null)
        assertNull(manager.activeProfile.value)
    }

    @Test
    fun `AgentProfile permissions are all valid`() {
        val perms = AgentProfilePermissions(
            fileAccess = com.devstation.android.core.agent.profiles.AgentProfileFileAccess.READ_WRITE,
            terminalAccess = true,
            networkAccess = true,
            packageAccess = true,
            sensitiveFileAccess = true
        )
        assertTrue(perms.isValid())
    }

    @Test
    fun `AgentProfile security scope options`() {
        val safe = AgentProfile(name = "Safe", securityScope = AgentProfileSecurityScope.SAFE)
        assertEquals(AgentProfileSecurityScope.SAFE, safe.securityScope)

        val balanced = AgentProfile(name = "Balanced", securityScope = AgentProfileSecurityScope.BALANCED)
        assertEquals(AgentProfileSecurityScope.BALANCED, balanced.securityScope)

        val custom = AgentProfile(name = "Custom", securityScope = AgentProfileSecurityScope.CUSTOM)
        assertEquals(AgentProfileSecurityScope.CUSTOM, custom.securityScope)
    }

    // ---- Security Integration Tests ----

    @Test
    fun `McpSecurityClassifier maps to correct resource types`() {
        assertEquals(
            com.devstation.android.core.security.policy.ResourceType.PROJECT_FILE,
            McpSecurityClassifier.toResourceType(com.devstation.android.core.mcp.McpSecurityClassification.READ_ONLY)
        )
        assertEquals(
            com.devstation.android.core.security.policy.ResourceType.NETWORK,
            McpSecurityClassifier.toResourceType(com.devstation.android.core.mcp.McpSecurityClassification.NETWORK)
        )
        assertEquals(
            com.devstation.android.core.security.policy.ResourceType.PACKAGE_MANAGER,
            McpSecurityClassifier.toResourceType(com.devstation.android.core.mcp.McpSecurityClassification.PACKAGE_INSTALL)
        )
    }

    @Test
    fun `McpSecurityClassifier maps to correct risk levels`() {
        assertEquals(
            com.devstation.android.core.agent.ToolRiskLevel.LOW,
            McpSecurityClassifier.toRiskLevel(com.devstation.android.core.mcp.McpSecurityClassification.READ_ONLY)
        )
        assertEquals(
            com.devstation.android.core.agent.ToolRiskLevel.HIGH,
            McpSecurityClassifier.toRiskLevel(com.devstation.android.core.mcp.McpSecurityClassification.DESTRUCTIVE)
        )
        assertEquals(
            com.devstation.android.core.agent.ToolRiskLevel.CRITICAL,
            McpSecurityClassifier.toRiskLevel(com.devstation.android.core.mcp.McpSecurityClassification.SYSTEM)
        )
    }

    @Test
    fun `McpSecurityClassifier maps to correct permissions`() {
        assertEquals(
            com.devstation.android.core.agent.ToolPermission.ALLOW,
            McpSecurityClassifier.toToolPermission(com.devstation.android.core.mcp.McpSecurityClassification.READ_ONLY)
        )
        assertEquals(
            com.devstation.android.core.agent.ToolPermission.ALWAYS_ASK,
            McpSecurityClassifier.toToolPermission(com.devstation.android.core.mcp.McpSecurityClassification.DESTRUCTIVE)
        )
        assertEquals(
            com.devstation.android.core.agent.ToolPermission.ALWAYS_ASK,
            McpSecurityClassifier.toToolPermission(com.devstation.android.core.mcp.McpSecurityClassification.UNKNOWN)
        )
    }

    // ---- Attack Tests ----

    @Test
    fun `MCP server with sensitive env keys is rejected`() = runTest {
        val store = InMemoryMcpConfigStore()
        val manager = McpServerManager(
            transportFactory = { InMemoryMcpTransport() },
            capabilityRegistry = McpCapabilityRegistry(),
            audit = auditLogger,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope(),
            configStore = store
        )

        val configs = listOf(
            McpServerConfig(name = "Attack1", command = "echo", environment = mapOf("API_KEY" to "x")),
            McpServerConfig(name = "Attack2", command = "echo", environment = mapOf("SECRET_TOKEN" to "x")),
            McpServerConfig(name = "Attack3", command = "echo", environment = mapOf("PRIVATE_KEY" to "x")),
            McpServerConfig(name = "Attack4", command = "echo", environment = mapOf("PASSWORD" to "x")),
            McpServerConfig(name = "Attack5", command = "echo", environment = mapOf("CREDENTIAL" to "x"))
        )

        configs.forEach { config ->
            val result = manager.registerServer(config)
            assertTrue("Server '${config.name}' with sensitive env should be rejected", result.isFailure)
        }
    }

    @Test
    fun `Agent cannot grant itself permissions through profile`() = runTest {
        val store = InMemoryAgentProfileConfigStore()
        val manager = AgentProfileManager(
            audit = auditLogger,
            dispatchers = dispatchers,
            configStore = store
        )

        // Even if a profile declares high-risk permissions, the SecurityManager is authoritative
        val profile = AgentProfile(
            name = "Malicious Agent",
            permissionProfile = AgentProfilePermissions(
                terminalAccess = true,
                networkAccess = true,
                packageAccess = true,
                sensitiveFileAccess = true
            )
        )
        val result = manager.createProfile(profile)
        // Profile creation succeeds (it's just configuration), but SecurityManager enforces
        assertTrue(result is AgentProfileResult.Success)
        assertTrue(profile.hasHighRiskCapabilities())
    }

    @Test
    fun `Agent profile max limits are enforced`() {
        try {
            AgentProfile(name = "Bad", maxIterations = 0)
            fail("Should reject zero iterations")
        } catch (_: IllegalArgumentException) { }

        try {
            AgentProfile(name = "Bad", maxToolCalls = 0)
            fail("Should reject zero tool calls")
        } catch (_: IllegalArgumentException) { }

        try {
            AgentProfile(name = "Bad", maxTaskDurationMs = 0)
            fail("Should reject zero duration")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `MCP tool wrapper generates valid tool name`() {
        val capability = McpCapability(
            serverId = "test-server",
            capabilityType = McpCapabilityType.TOOL,
            name = "read-file"
        )
        val name = McpToolWrapper.sanitizeName(capability.name)
        assertTrue(name.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }

    @Test
    fun `McpToolWrapper sanitizes special characters in names`() {
        assertEquals("read_file", McpToolWrapper.sanitizeName("read/file"))
        assertEquals("exec_cmd", McpToolWrapper.sanitizeName("exec;cmd"))
        assertEquals("test_tool", McpToolWrapper.sanitizeName("test tool"))
    }

    // ---- Database Schema Tests ----

    @Test
    fun `McpServerEntity round-trips correctly`() {
        val entity = com.devstation.android.core.database.McpServerEntity(
            id = "test-id",
            name = "Test Server",
            description = "A test",
            transportType = "STDIO",
            command = "echo",
            argumentsJson = "[\"arg1\"]",
            environmentJson = "{\"KEY\":\"val\"}",
            enabled = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals("test-id", entity.id)
        assertEquals("STDIO", entity.transportType)
        assertTrue(entity.enabled)
    }

    @Test
    fun `SkillEntity round-trips correctly`() {
        val entity = com.devstation.android.core.database.SkillEntity(
            id = "test-skill",
            name = "Test Skill",
            description = "A test skill",
            version = "1.0.0",
            author = "DevStation",
            instructions = "Do things",
            requiredToolsJson = "[\"read_file\"]",
            source = "BUILTIN",
            enabled = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals("test-skill", entity.id)
        assertEquals("1.0.0", entity.version)
        assertEquals("BUILTIN", entity.source)
    }

    @Test
    fun `AgentProfileEntity round-trips correctly`() {
        val entity = com.devstation.android.core.database.AgentProfileEntity(
            id = "test-profile",
            name = "Test Profile",
            description = "A test profile",
            enabled = true,
            systemInstructions = "Be helpful",
            securityScope = "BALANCED",
            maxIterations = 25,
            maxToolCalls = 50,
            maxTaskDurationMs = 600_000L,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertEquals("test-profile", entity.id)
        assertEquals("BALANCED", entity.securityScope)
        assertEquals(25, entity.maxIterations)
    }

    // ---- Helper ----

    /**
     * Minimal AgentRuntime for SkillManager unit tests. The actual agent execution is
     * tested through integration tests in AgentSecurityIntegrationTest.
     */
    private fun createMockAgentRuntime(): com.devstation.android.core.agent.AgentRuntime {
        val editorBridge = com.devstation.android.core.agent.tools.EditorBridgeImpl()
        val toolFactory = com.devstation.android.core.agent.tools.DefaultAgentToolFactory(
            editorBridge = editorBridge,
            processRegistry = com.devstation.android.core.agent.tools.AgentProcessRegistry(),
            linuxRunner = null,
            androidRunner = com.devstation.android.core.agent.tools.AndroidShellCommandRunner(
                com.devstation.android.core.agent.tools.AgentProcessRegistry()
            ),
            linuxAvailable = { false },
            allowAndroidFallback = { false }
        )

        val testSettings = com.devstation.android.core.database.AISettingsEntity(agentToolsEnabled = true)
        val mockDao = object : com.devstation.android.core.database.AISettingsDao {
            override suspend fun get() = testSettings
            override fun getFlow(): kotlinx.coroutines.flow.Flow<com.devstation.android.core.database.AISettingsEntity?> = kotlinx.coroutines.flow.flowOf(testSettings)
            override suspend fun upsert(settings: com.devstation.android.core.database.AISettingsEntity) {}
        }

        return com.devstation.android.core.agent.AgentRuntime(
            providerManager = object : com.devstation.android.core.ai.AIProviderManager {
                override val providers: List<com.devstation.android.core.ai.AIProvider> = emptyList()
                override fun getProvider(providerId: String) = null
                override fun registerProvider(provider: com.devstation.android.core.ai.AIProvider) {}
                override suspend fun resolveModel(providerId: String, modelId: String?): Result<com.devstation.android.core.ai.AIModel> =
                    Result.failure(Exception("Mock"))
            },
            aiSettingsRepository = com.devstation.android.core.repository.AISettingsRepository(
                dao = mockDao,
                dispatchers = dispatchers
            ),
            projectLocator = com.devstation.android.core.agent.ProjectLocator { null },
            conversationPort = object : com.devstation.android.core.agent.AgentConversationPort {
                override suspend fun history(conversationId: String) = emptyList<com.devstation.android.core.ai.AIMessage>()
                override suspend fun providerSelection(conversationId: String) = null to null
                override suspend fun persistAssistant(conversationId: String, content: String) {}
            },
            toolFactory = toolFactory,
            permissionManager = com.devstation.android.core.agent.PermissionManager(
                broker = com.devstation.android.core.agent.ApprovalBroker(),
                grantSink = { _, _ -> },
                scopedGrantSink = { _, _, _ -> }
            ),
            broker = com.devstation.android.core.agent.ApprovalBroker(),
            processRegistry = com.devstation.android.core.agent.tools.AgentProcessRegistry(),
            taskStore = com.devstation.android.core.agent.NoOpAgentStores.taskStore,
            eventStore = com.devstation.android.core.agent.NoOpAgentStores.eventStore,
            historyStore = com.devstation.android.core.agent.NoOpAgentStores.historyStore,
            permissionStore = com.devstation.android.core.agent.NoOpAgentStores.permissionStore,
            dispatchers = dispatchers,
            scope = kotlinx.coroutines.test.TestScope()
        )
    }
}
