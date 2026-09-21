package com.devstation.android.core.security.policy

import com.devstation.android.core.agent.SecretRedactor
import com.devstation.android.core.agent.ToolRiskLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/** §34: the security event vocabulary. */
enum class SecurityEventType {
    PERMISSION_REQUESTED,
    PERMISSION_GRANTED,
    PERMISSION_DENIED,
    PERMISSION_REVOKED,
    TOOL_EXECUTED,
    TOOL_BLOCKED,
    PATH_BLOCKED,
    NETWORK_BLOCKED,
    SECRET_ACCESS_BLOCKED,
    PROCESS_STARTED,
    PROCESS_TERMINATED,
    PROCESS_TIMEOUT,
    RESOURCE_LIMIT_REACHED,
    AGENT_CANCELLED,
    SECURITY_POLICY_CHANGED
}

/** Outcome recorded with an event, kept separate from the decision type for readable history. */
enum class AuditDecision { ALLOWED, ASKED, DENIED, BLOCKED, RECORDED }

/**
 * One auditable security decision. [summary] is bounded and redacted before it is stored (§35):
 * never a full terminal output, never an API key, never a cookie.
 */
data class SecurityAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: SecurityEventType,
    val decision: AuditDecision,
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW,
    val projectId: String? = null,
    val taskId: String? = null,
    val sessionId: String? = null,
    val agentId: String? = null,
    val toolName: String? = null,
    val action: SecurityAction = SecurityAction.UNKNOWN,
    val resourceType: ResourceType = ResourceType.UNKNOWN,
    val summary: String
)

/** Persistence port for audit events. */
interface SecurityAuditStore {
    suspend fun append(event: SecurityAuditEvent)
    suspend fun recent(limit: Int = 200): List<SecurityAuditEvent>
    fun observe(limit: Int = 200): Flow<List<SecurityAuditEvent>>
    /** §36 retention: delete everything older than [timestamp]. Returns rows removed. */
    suspend fun deleteOlderThan(timestamp: Long): Int
    suspend fun clear(): Int
}

/** In-memory store used by unit tests and as a fallback when persistence is unavailable. */
class InMemorySecurityAuditStore(initial: List<SecurityAuditEvent> = emptyList()) : SecurityAuditStore {
    private val events = initial.toMutableList()
    override suspend fun append(event: SecurityAuditEvent) {
        events.add(event)
        if (events.size > MAX_EVENTS) events.removeAt(0)
    }

    override suspend fun recent(limit: Int): List<SecurityAuditEvent> =
        events.takeLast(limit).reversed()

    override fun observe(limit: Int): Flow<List<SecurityAuditEvent>> = flowOf(events.takeLast(limit).reversed())

    override suspend fun deleteOlderThan(timestamp: Long): Int {
        val before = events.size
        events.removeAll { it.timestamp < timestamp }
        return before - events.size
    }

    override suspend fun clear(): Int {
        val removed = events.size
        events.clear()
        return removed
    }

    private companion object {
        const val MAX_EVENTS = 1_000
    }
}

/**
 * Writes audit events. Redacts and bounds every summary first, so no caller can accidentally store
 * a secret or a megabyte of terminal output.
 */
class SecurityAuditLogger(
    private val store: SecurityAuditStore = InMemorySecurityAuditStore(),
    private val maxSummaryChars: Int = MAX_SUMMARY_CHARS
) {

    suspend fun log(event: SecurityAuditEvent): SecurityAuditEvent {
        val safe = event.copy(summary = bound(SecretRedactor.redact(event.summary)))
        store.append(safe)
        return safe
    }

    suspend fun log(
        type: SecurityEventType,
        decision: AuditDecision,
        request: SecurityRequest? = null,
        riskLevel: ToolRiskLevel? = null,
        summary: String
    ): SecurityAuditEvent = log(
        SecurityAuditEvent(
            type = type,
            decision = decision,
            riskLevel = riskLevel ?: request?.riskLevel ?: ToolRiskLevel.LOW,
            projectId = request?.projectId,
            taskId = request?.taskId,
            sessionId = request?.sessionId,
            agentId = request?.agentId,
            toolName = request?.toolName,
            action = request?.action ?: SecurityAction.UNKNOWN,
            resourceType = request?.resourceType ?: ResourceType.UNKNOWN,
            summary = summary
        )
    )

    suspend fun recent(limit: Int = 200): List<SecurityAuditEvent> = store.recent(limit)

    fun observe(limit: Int = 200): Flow<List<SecurityAuditEvent>> = store.observe(limit)

    /** §36: keep only [retentionDays] of history. */
    suspend fun prune(retentionDays: Int, now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - retentionDays.coerceAtLeast(1) * DAY_MS
        return store.deleteOlderThan(cutoff)
    }

    suspend fun clear(): Int = store.clear()

    private fun bound(text: String): String =
        if (text.length <= maxSummaryChars) text else text.take(maxSummaryChars) + "…"

    companion object {
        const val MAX_SUMMARY_CHARS = 300
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Used where auditing is not wired (unit tests, dry runs). */
        val NoOp = SecurityAuditLogger(InMemorySecurityAuditStore())
    }
}
