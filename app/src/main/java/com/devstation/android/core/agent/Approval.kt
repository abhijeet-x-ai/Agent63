package com.devstation.android.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** What the user is being asked to allow, with enough detail to make an informed decision. */
data class ApprovalRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val toolName: String,
    /** e.g. "Run terminal command", "Modify file", "Delete file". */
    val title: String,
    /** The exact target: file path, command, etc. */
    val target: String,
    /** Extra context shown under the target (reason, directory, risk explanation). */
    val detail: String,
    val riskLevel: ToolRiskLevel,
    val scope: PermissionScope = PermissionScope.PER_TASK,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ApprovalOutcome { ALLOW_ONCE, ALLOW_FOR_TASK, DENY }

data class ApprovalDecision(val outcome: ApprovalOutcome) {
    companion object {
        val Deny = ApprovalDecision(ApprovalOutcome.DENY)
        val AllowOnce = ApprovalDecision(ApprovalOutcome.ALLOW_ONCE)
        val AllowForTask = ApprovalDecision(ApprovalOutcome.ALLOW_FOR_TASK)
    }
}

/**
 * Suspends a tool call until the user answers, or until the task is cancelled.
 *
 * This is the ONLY place execution can be gated, and the runtime always awaits it *before*
 * handing control to a tool body (Phase 6 §28 — approval must be real).
 */
class ApprovalBroker {

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    private val mutex = Mutex()
    private var deferred: CompletableDeferred<ApprovalDecision>? = null

    /** Requests approval for [request] and suspends until [submit] or cancellation. */
    suspend fun request(request: ApprovalRequest): ApprovalDecision = mutex.withLock {
        val waiter = CompletableDeferred<ApprovalDecision>()
        deferred = waiter
        _pending.value = request
        try {
            // Cancelling the owning task cancels this await, which unwinds the tool call.
            waiter.await()
        } finally {
            _pending.value = null
            deferred = null
        }
    }

    /** Called by the UI. Returns false when there is nothing pending. */
    fun submit(decision: ApprovalDecision): Boolean {
        val waiter = deferred ?: return false
        return waiter.complete(decision)
    }

    /** Deny anything outstanding (emergency stop, task cancellation, app shutdown). */
    fun denyPending() {
        _pending.value = null
        deferred?.complete(ApprovalDecision.Deny)
        deferred = null
    }
}
