package com.devstation.android.core.security

import com.devstation.android.core.agent.AgentTestDispatchers
import com.devstation.android.core.agent.ApprovalBroker
import com.devstation.android.core.agent.PermissionManager
import com.devstation.android.core.agent.PermissionScope
import com.devstation.android.core.agent.ToolRiskLevel
import com.devstation.android.core.agent.createTempProject
import com.devstation.android.core.agent.deleteTempProject
import com.devstation.android.core.agent.tools.AgentProcessRegistry
import com.devstation.android.core.security.policy.AgentSecurityMode
import com.devstation.android.core.security.policy.AuditDecision
import com.devstation.android.core.security.policy.CategoryPolicy
import com.devstation.android.core.security.policy.DiagnosticStatus
import com.devstation.android.core.security.policy.InMemorySecurityAuditStore
import com.devstation.android.core.security.policy.PermissionCategory
import com.devstation.android.core.security.policy.ProjectSecuritySettings
import com.devstation.android.core.security.policy.RoomSecurityAuditStore
import com.devstation.android.core.security.policy.SecurityAuditEvent
import com.devstation.android.core.security.policy.SecurityAuditLogger
import com.devstation.android.core.security.policy.SecurityDiagnostics
import com.devstation.android.core.security.policy.SecurityEventType
import com.devstation.android.core.security.policy.SecurityManager
import com.devstation.android.core.security.policy.SecurityPolicy
import com.devstation.android.core.security.policy.SecurityPolicyEngine
import com.devstation.android.core.security.policy.SecurityPolicyRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Phase 7 §34–§36 (audit), §47 (diagnostics) and §31/§32/§39 (policy + revocation).
 */
class SecurityAuditAndDiagnosticsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = createTempProject(mapOf("src/App.kt" to "fun main() {}\n"))
    }

    @After
    fun tearDown() = deleteTempProject(root)

    private fun logger(store: InMemorySecurityAuditStore = InMemorySecurityAuditStore()) =
        SecurityAuditLogger(store) to store

    // ---- audit ----

    @Test
    fun `audit summaries are redacted before they are stored`() = runBlocking {
        val (log, _) = logger()
        log.log(
            type = SecurityEventType.TOOL_BLOCKED,
            decision = AuditDecision.BLOCKED,
            summary = "Authorization: Bearer sk-live-ABCDEFGHIJKLMNOPQRSTUVWX1234567890 password=hunter2"
        )
        val stored = log.recent(1).single()
        assertFalse(stored.summary.contains("sk-live-ABCDEFGHIJKLMNOPQRSTUVWX1234567890"))
        assertFalse(stored.summary.contains("hunter2"))
    }

    @Test
    fun `audit summaries are bounded`() = runBlocking {
        val (log, _) = logger()
        log.log(
            type = SecurityEventType.TOOL_EXECUTED,
            decision = AuditDecision.ALLOWED,
            summary = "x".repeat(5_000)
        )
        val stored = log.recent(1).single()
        assertTrue(stored.summary.length <= SecurityAuditLogger.MAX_SUMMARY_CHARS + 1)
    }

    @Test
    fun `audit retention deletes older events and clear removes everything`() = runBlocking {
        val (log, store) = logger()
        val now = System.currentTimeMillis()
        store.append(
            SecurityAuditEvent(
                timestamp = now - 40L * 24 * 60 * 60 * 1000,
                type = SecurityEventType.TOOL_EXECUTED,
                decision = AuditDecision.ALLOWED,
                summary = "old"
            )
        )
        log.log(SecurityEventType.TOOL_EXECUTED, AuditDecision.ALLOWED, summary = "new")
        assertEquals(2, log.recent(10).size)

        val removed = log.prune(retentionDays = 30, now = now)
        assertEquals(1, removed)
        assertEquals(listOf("new"), log.recent(10).map { it.summary })

        assertEquals(1, log.clear())
        assertTrue(log.recent(10).isEmpty())
    }

    @Test
    fun `audit events record identity, risk and decision`() = runBlocking {
        val (log, _) = logger()
        val stored = log.log(
            type = SecurityEventType.PATH_BLOCKED,
            decision = AuditDecision.BLOCKED,
            riskLevel = ToolRiskLevel.HIGH,
            summary = "blocked a path"
        )
        assertEquals(SecurityEventType.PATH_BLOCKED, stored.type)
        assertEquals(AuditDecision.BLOCKED, stored.decision)
        assertEquals(ToolRiskLevel.HIGH, stored.riskLevel)
    }

    @Test
    fun `the room-backed audit store round-trips events`() = runBlocking {
        val dao = FakeSecurityEventDao()
        val store = RoomSecurityAuditStore(dao, AgentTestDispatchers)
        val log = SecurityAuditLogger(store)
        log.log(SecurityEventType.PERMISSION_GRANTED, AuditDecision.ALLOWED, summary = "granted read_file")
        val read = log.recent(5).single()
        assertEquals(SecurityEventType.PERMISSION_GRANTED, read.type)
        assertEquals("granted read_file", read.summary)
    }

    // ---- diagnostics ----

    private fun diagnostics(
        audit: SecurityAuditLogger? = null,
        engine: SecurityPolicyEngine? = null
    ) = SecurityDiagnostics(
        engine = engine,
        processes = AgentProcessRegistry(),
        audit = audit,
        protectedPaths = listOf("/data/data/", "/data/user/", "/data/misc/keystore")
    )

    @Test
    fun `diagnostics pass against a real project`() = runBlocking {
        val (log, _) = logger()
        val checks = diagnostics(audit = log).run(root, "task-1")
        val failed = checks.filter { it.status == DiagnosticStatus.FAIL }
        assertTrue("unexpected failures: ${failed.map { it.title to it.detail }}", failed.isEmpty())
        assertTrue(checks.any { it.id == "project_root_containment" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "android_private_paths" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "sensitive_files" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "terminal_policy" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "network_policy" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "permission_scopes" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "resource_limits" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "audit_logging" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "audit_redaction" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "process_ownership" && it.status == DiagnosticStatus.PASS })
        assertTrue(checks.any { it.id == "symlink_escape" && (it.status == DiagnosticStatus.PASS || it.status == DiagnosticStatus.WARNING) })
    }

    @Test
    fun `diagnostics never report pass for a check they could not run`() = runBlocking {
        // No project and no audit store: those checks must be WARNING, never PASS.
        val checks = diagnostics().run(null, null)
        val unverified = checks.filter { it.status == DiagnosticStatus.WARNING }.map { it.id }
        assertTrue(unverified.contains("project_root_containment"))
        assertTrue(unverified.contains("audit_logging"))
        assertTrue(unverified.contains("policy_engine"))
        checks.forEach { check ->
            assertTrue(check.title.isNotBlank())
            assertTrue(check.detail.isNotBlank())
        }
    }

    @Test
    fun `even a maximally permissive category policy cannot open private paths or credentials`() =
        runBlocking {
            val permissive = SecurityPolicyEngine(
                policyProvider = {
                    SecurityPolicy.DEFAULT
                        .withCategory(PermissionCategory.FILES, CategoryPolicy.ALLOW)
                        .withCategory(PermissionCategory.SENSITIVE_FILES, CategoryPolicy.ALLOW)
                },
                projectSettings = { null }
            )
            val checks = diagnostics(engine = permissive).run(root, "task-1")
            val engineCheck = checks.single { it.id == "policy_engine" }
            assertEquals(engineCheck.detail, DiagnosticStatus.PASS, engineCheck.status)
        }

    @Test
    fun `diagnostics engine checks exercise the wired engine`() = runBlocking {
        val engine = SecurityPolicyEngine(
            policyProvider = { SecurityPolicy.DEFAULT },
            audit = SecurityAuditLogger(InMemorySecurityAuditStore())
        )
        val checks = diagnostics(engine = engine).run(root, "task-1")
        val engineCheck = checks.single { it.id == "policy_engine" }
        assertEquals(engineCheck.detail, DiagnosticStatus.PASS, engineCheck.status)
    }

    // ---- SecurityManager ----

    private class ManagerHarness {
        val settingsDao = FakeSecuritySettingsDao()
        val projectDao = FakeProjectSecuritySettingsDao()
        val grantDao = FakePermissionGrantDao()
        val eventDao = FakeSecurityEventDao()
        val repository = SecurityPolicyRepository(settingsDao, projectDao, grantDao, AgentTestDispatchers)
        /** Mirrors AppContainer: session/project grants are persisted with their scope identity. */
        val permissions = PermissionManager(
            broker = ApprovalBroker(),
            scopedGrantSink = { scope, scopeId, toolName ->
                when (scope) {
                    PermissionScope.SESSION -> repository.grant(scope, toolName, sessionId = scopeId)
                    else -> repository.grant(scope, toolName, projectId = scopeId)
                }
            }
        )
        val audit = SecurityAuditLogger(RoomSecurityAuditStore(eventDao, AgentTestDispatchers))
        val sessionId = UUID.randomUUID().toString()
        val manager = SecurityManager(
            repository = repository,
            permissions = permissions,
            audit = audit,
            diagnostics = SecurityDiagnostics(processes = AgentProcessRegistry()),
            sessionId = sessionId
        )

        fun nextProcess(): SecurityManager = SecurityManager(
            repository = repository,
            permissions = permissions,
            audit = audit,
            diagnostics = SecurityDiagnostics(processes = AgentProcessRegistry()),
            sessionId = UUID.randomUUID().toString()
        )
    }

    @Test
    fun `the security mode can be changed only through the manager and is persisted`() = runBlocking {
        val h = ManagerHarness()
        assertEquals(AgentSecurityMode.BALANCED, h.repository.policy(null).mode)

        val impact = h.manager.setMode(AgentSecurityMode.SAFE)
        val policy = h.repository.policy(null)
        assertEquals(AgentSecurityMode.SAFE, policy.mode)
        // SAFE keeps read-only inspection possible but never grants outward access.
        assertEquals(CategoryPolicy.DEFAULT, policy.terminal)
        assertEquals(CategoryPolicy.ALWAYS_ASK, policy.network)
        assertEquals(CategoryPolicy.ALWAYS_ASK, policy.packages)
        assertEquals(CategoryPolicy.ALWAYS_ASK, policy.sensitiveFiles)
        assertEquals(CategoryPolicy.DENY, policy.credentials)
        // SAFE only tightens, so it reports no broadening.
        assertTrue(impact.isEmpty())
    }

    @Test
    fun `category policy cannot re-enable credentials`() = runBlocking {
        val h = ManagerHarness()
        h.manager.setCategory(PermissionCategory.CREDENTIALS, CategoryPolicy.ALLOW)
        assertEquals(CategoryPolicy.DENY, h.repository.policy(null).credentials)
    }

    @Test
    fun `session grants are revoked and never restored across sessions`() = runBlocking {
        val h = ManagerHarness()
        h.permissions.grantForSession(h.sessionId, "write_file")
        assertTrue(h.permissions.hasGrant(PermissionScope.SESSION, "write_file", null, h.sessionId, null))

        h.manager.revokeSession()
        assertFalse(h.permissions.hasGrant(PermissionScope.SESSION, "write_file", null, h.sessionId, null))
        assertTrue(h.grantDao.all().none { it.scope == PermissionScope.SESSION.name })
    }

    @Test
    fun `project grants survive a restart but session grants do not`() = runBlocking {
        val h = ManagerHarness()
        h.permissions.grantForProject("p1", "write_file")
        h.permissions.grantForSession(h.sessionId, "run_terminal_command")
        assertTrue(h.grantDao.all().any { it.scope == PermissionScope.PROJECT.name })

        // Simulate a new app process with a new session id.
        h.nextProcess().initializeSessionStore()

        assertTrue(h.grantDao.all().none { it.scope == PermissionScope.SESSION.name })
        assertTrue(h.permissions.hasProjectGrant("p1", "write_file"))
    }

    @Test
    fun `revoking everything clears task session and project grants`() = runBlocking {
        val h = ManagerHarness()
        h.permissions.grantForTask("t1", "write_file")
        h.permissions.grantForSession(h.sessionId, "write_file")
        h.permissions.grantForProject("p1", "write_file")

        h.manager.resetAllPermissions()

        assertFalse(h.permissions.hasTaskGrant("t1", "write_file"))
        assertFalse(h.permissions.hasSessionGrant(h.sessionId, "write_file"))
        assertFalse(h.permissions.hasProjectGrant("p1", "write_file"))
        assertTrue(h.grantDao.all().isEmpty())
    }

    @Test
    fun `project security settings are persisted and defaults stay restrictive`() = runBlocking {
        val h = ManagerHarness()
        val defaults = h.manager.projectSettings("p1")
        assertTrue(defaults.allowFileModification)
        assertFalse(defaults.denies(PermissionCategory.FILES))
        // Credentials are denied no matter what the per-project switches say.
        assertTrue(defaults.denies(PermissionCategory.CREDENTIALS))

        h.manager.setProjectSettings(
            ProjectSecuritySettings.defaults("p1").copy(allowNetwork = false, allowSensitiveFileAccess = false)
        )
        val updated = h.manager.projectSettings("p1")
        assertFalse(updated.allowNetwork)
        assertFalse(updated.allowSensitiveFileAccess)
        assertTrue(updated.denies(PermissionCategory.NETWORK))
        assertFalse(updated.denies(PermissionCategory.FILES))
    }

    @Test
    fun `the overview reports the real grant and event counts`() = runBlocking {
        val h = ManagerHarness()
        h.permissions.grantForSession(h.sessionId, "write_file")
        h.manager.setMode(AgentSecurityMode.SAFE)

        val overview = h.manager.overview("p1", "t1")
        assertEquals(h.sessionId, overview.sessionId)
        assertEquals(setOf("write_file"), overview.sessionGrantedTools)
        assertNotNull(overview.projectSettings)
        assertTrue(overview.auditEventCount >= 1)
    }

    @Test
    fun `retention changes are applied immediately`() = runBlocking {
        val h = ManagerHarness()
        val old = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000
        h.eventDao.insert(
            com.devstation.android.core.database.SecurityEventEntity(
                id = "old",
                timestamp = old,
                type = SecurityEventType.TOOL_EXECUTED.name,
                decision = AuditDecision.ALLOWED.name,
                risk = ToolRiskLevel.LOW.name,
                action = "UNKNOWN",
                resourceType = "UNKNOWN",
                summary = "old event"
            )
        )
        val removed = h.manager.setRetention(7)
        assertEquals(1, removed)
        assertEquals(7, h.repository.policy(null).auditRetentionDays)
    }
}
