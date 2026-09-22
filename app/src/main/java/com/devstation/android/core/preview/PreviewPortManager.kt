package com.devstation.android.core.preview

import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 9 §18/§19/§20: preview port allocation and ownership.
 *
 * A managed port is always associated with its project + server. One project's server can never
 * hijack another's managed port, and allocation actually probes the OS instead of assuming the
 * common dev ports are free.
 */
class PreviewPortManager {

    data class PortLease(
        val port: Int,
        val projectId: String,
        val serverId: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val leases = ConcurrentHashMap<Int, PortLease>()

    /** True when the OS would currently accept a bind on [port] for loopback. */
    fun isPortFree(port: Int): Boolean {
        if (port !in MIN_PORT..MAX_PORT) return false
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /** Allocate [requestedPort], or find the next free port at or above it when [allowAutoSelect]. */
    fun allocate(
        projectId: String,
        serverId: String,
        requestedPort: Int,
        allowAutoSelect: Boolean = true
    ): Result<Int> {
        // §18: port 0 means "let DevStation pick a free port" — never assume a common dev
        // port is available. Any other out-of-range port is rejected.
        if (requestedPort == 0) {
            // Try a few random dev-range candidates first, then fall back to a full scan.
            val rng = java.util.concurrent.ThreadLocalRandom.current()
            repeat(16) {
                val candidate = rng.nextInt(40_000, 60_000)
                if (isPortFree(candidate) && leases[candidate] == null) {
                    return recordAndReturn(projectId, serverId, candidate)
                }
            }
            var candidate = MIN_PORT
            while (candidate <= MAX_PORT) {
                if (isPortFree(candidate) && leases[candidate] == null) {
                    return recordAndReturn(projectId, serverId, candidate)
                }
                candidate++
            }
            return Result.failure(IllegalStateException("No free port available."))
        }
        if (requestedPort !in MIN_PORT..MAX_PORT) {
            return Result.failure(IllegalArgumentException("Port must be between $MIN_PORT and $MAX_PORT (or 0 for auto-select)."))
        }
        val existing = leases[requestedPort]
        if (existing != null) {
            if (existing.projectId != projectId) {
                return Result.failure(
                    IllegalStateException(
                        "Port $requestedPort is managed by another project's preview server."
                    )
                )
            }
            if (existing.serverId != serverId) {
                return Result.failure(
                    IllegalStateException("Port $requestedPort is already managed by another preview server.")
                )
            }
            // Same server re-allocating its own lease: keep it.
            return Result.success(requestedPort)
        }
        if (!isPortFree(requestedPort)) {
            if (!allowAutoSelect) {
                return Result.failure(IllegalStateException("Port $requestedPort is not available."))
            }
            var candidate = requestedPort + 1
            while (candidate <= MAX_PORT) {
                if (isPortFree(candidate) && leases[candidate] == null) {
                    return recordAndReturn(projectId, serverId, candidate)
                }
                candidate++
            }
            return Result.failure(IllegalStateException("No free port found near $requestedPort."))
        }
        return recordAndReturn(projectId, serverId, requestedPort)
    }

    private fun recordAndReturn(projectId: String, serverId: String, port: Int): Result<Int> {
        leases[port] = PortLease(port, projectId, serverId)
        return Result.success(port)
    }

    /** Release a port owned by [serverId]. Returns true when a lease was removed. */
    fun release(serverId: String): Boolean {
        val entry = leases.entries.firstOrNull { it.value.serverId == serverId } ?: return false
        leases.remove(entry.key)
        return true
    }

    /** The lease for a port, or null. Used for cross-project checks (§19/§38). */
    fun leaseFor(port: Int): PortLease? = leases[port]

    /** True when [projectId] owns the lease on [port]. */
    fun isOwnedBy(projectId: String, port: Int): Boolean =
        leases[port]?.projectId == projectId

    fun ownedPorts(projectId: String): List<Int> =
        leases.filterValues { it.projectId == projectId }.keys.toList()

    fun releaseAll() = leases.clear()

    companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
