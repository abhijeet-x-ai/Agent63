package com.devstation.android.core.remote

/**
 * Phase 11: Remote Workspace + SSH — pure model layer.
 *
 * No Android, Room, or sshj types here: everything in this file is plain
 * Kotlin so it can be unit-tested on the JVM and reused by the data layer,
 * the session manager, the sync engine, and the UI.
 */

/** How a host authenticates. The secret itself never lives in a profile —
 * it is stored in the Keystore-backed credential store under the host id. */
enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY
}

data class SshHostProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val hostKeyFingerprint: String? = null,
    val hostKeyType: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastConnectedAt: Long? = null
) {
    /** `user@host:port` label used across the UI. */
    val displayAddress: String get() = "$username@$host:$port"

    /** Alias prefix for this host's secrets in the credential store. */
    val credentialAliasPrefix: String get() = "ssh/$id"
}

/** Lifecycle of the single active remote session. */
enum class RemoteSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/** Bounded result of one remote command execution. */
data class RemoteExecResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean
)

/** One file as seen on either side of a sync. Paths are relative, `/`-separated. */
data class SyncFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val isDirectory: Boolean
)

/** One file the planner decided must be uploaded, with the reason why. */
data class SyncItem(
    val relativePath: String,
    val sizeBytes: Long,
    val reason: Reason
) {
    enum class Reason { NEW, SIZE_CHANGED, NEWER }
}

/** Output of [SyncPlanner]: the minimal upload set plus accounting. */
data class SyncPlan(
    val items: List<SyncItem>,
    val totalBytes: Long,
    val skippedCount: Int,
    val rejectedPaths: List<String>
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * Form-level validation for host input, shared by the editor UI and tests.
 * Returns human-readable problems; an empty list means the input is valid.
 */
fun validateSshHostInput(
    name: String,
    host: String,
    port: Int,
    username: String
): List<String> {
    val problems = mutableListOf<String>()
    if (name.isBlank()) problems += "Name is required"
    if (host.isBlank()) {
        problems += "Host is required"
    } else if (host.any { it.isWhitespace() }) {
        problems += "Host must not contain spaces"
    }
    if (port !in 1..65535) problems += "Port must be between 1 and 65535"
    if (username.isBlank()) {
        problems += "Username is required"
    } else if (username.any { it.isWhitespace() }) {
        problems += "Username must not contain spaces"
    }
    return problems
}
