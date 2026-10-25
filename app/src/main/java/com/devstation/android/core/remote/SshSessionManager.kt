package com.devstation.android.core.remote

import com.devstation.android.future.remote.RemoteWorkspace
import com.devstation.android.future.remote.SshConnectionConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 11: the single active SSH session, implementing the
 * [RemoteWorkspace] contract from `future/remote`.
 *
 * Responsibilities:
 *  - resolve a connect request to a saved [SshHostProfile] (the app flow) or
 *    an ad-hoc profile keyed by the contract's `keyAlias`
 *  - load the profile's secret from [SshCredentialManager] at connect time
 *    only — secrets are never cached in fields after the attempt
 *  - drive the [RemoteSessionState] machine and surface typed failures
 *    ([HostKeyUnknownException] / [HostKeyChangedException]) so the UI can
 *    run the trust flow and retry
 *  - bound `exec` output/timeout and run `syncLocalToRemote` via
 *    [SyncPlanner] + [SftpSyncEngine]
 *
 * The [SshClient] is created per connection through [clientFactory], which
 * keeps this class fully unit-testable with a fake client.
 */
class SshSessionManager(
    private val repository: SshHostRepository,
    private val credentials: SshCredentialManager,
    private val clientFactory: () -> SshClient = { SshjClient() },
    private val onAuditEvent: (String) -> Unit = {}
) : RemoteWorkspace {

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _activeProfile = MutableStateFlow<SshHostProfile?>(null)
    val activeProfile: StateFlow<SshHostProfile?> = _activeProfile.asStateFlow()

    /** The last connect/exec failure, typed so the UI can react precisely. */
    private val _lastFailure = MutableStateFlow<Throwable?>(null)
    val lastFailure: StateFlow<Throwable?> = _lastFailure.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    private var client: SshClient? = null

    override val isConnected: Flow<Boolean> =
        sessionState.map { it == RemoteSessionState.CONNECTED }

    /** Connects to a saved profile by id (the normal app flow). */
    suspend fun connectProfile(profileId: String): Result<Unit> {
        val profile = repository.profile(profileId)
            ?: return fail(IllegalArgumentException("No saved host with id $profileId"))
        return connectTo(profile)
    }

    /**
     * Contract entry point: resolves the saved profile matching
     * host/port/username; when none exists and `keyAlias` is provided, an
     * ad-hoc profile keyed by that alias is used (secrets must already exist
     * under `ssh/<keyAlias>/…`).
     */
    override suspend fun connect(config: SshConnectionConfig): Result<Unit> {
        val saved = repository.profiles().firstOrNull {
            it.host == config.host && it.port == config.port && it.username == config.username
        }
        val profile = saved ?: run {
            val alias = config.keyAlias
                ?: return fail(
                    IllegalArgumentException(
                        "No saved host profile for ${config.username}@${config.host}:${config.port}"
                    )
                )
            SshHostProfile(
                id = alias,
                name = "${config.username}@${config.host}",
                host = config.host,
                port = config.port,
                username = config.username,
                authType = if (credentials.hasSecret(alias, SshAuthType.PRIVATE_KEY)) {
                    SshAuthType.PRIVATE_KEY
                } else {
                    SshAuthType.PASSWORD
                }
            )
        }
        return connectTo(profile)
    }

    private suspend fun connectTo(profile: SshHostProfile): Result<Unit> {
        disconnectInternal()
        _sessionState.value = RemoteSessionState.CONNECTING
        _lastFailure.value = null

        val auth: SshAuthMaterial = when (profile.authType) {
            SshAuthType.PASSWORD -> {
                val password = credentials.password(profile.id).getOrElse { return fail(it) }
                    ?: return fail(IllegalStateException("No password saved for ${profile.name}"))
                SshAuthMaterial.Password(password)
            }
            SshAuthType.PRIVATE_KEY -> {
                val key = credentials.keyMaterial(profile.id).getOrElse { return fail(it) }
                    ?: return fail(IllegalStateException("No private key saved for ${profile.name}"))
                SshAuthMaterial.PrivateKey(key.privateKeyPem, key.passphrase)
            }
        }
        val request = SshConnectRequest(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            auth = auth,
            storedHostKeyFingerprint = profile.hostKeyFingerprint
        )

        val newClient = clientFactory()
        val attempted = withTimeoutOrNull(request.connectTimeoutMs + GRACE_MS) {
            withContext(Dispatchers.IO) { runCatching { newClient.connect(request) } }
        }
        val result = attempted ?: run {
            // Blocking socket connects ignore coroutine cancellation; closing
            // the client is what actually unwinds the attempt.
            withContext(Dispatchers.IO) { runCatching { newClient.disconnect() } }
            Result.failure(IllegalStateException("Connection timed out"))
        }
        return result.fold(
            onSuccess = {
                client = newClient
                _activeProfile.value = profile
                _sessionState.value = RemoteSessionState.CONNECTED
                repository.markConnected(profile.id)
                onAuditEvent("SSH_CONNECT ${profile.displayAddress}")
                Result.success(Unit)
            },
            onFailure = { fail(it) }
        )
    }

    override suspend fun disconnect(): Result<Unit> {
        disconnectInternal()
        return Result.success(Unit)
    }

    private suspend fun disconnectInternal() {
        val existing = client
        client = null
        _activeProfile.value = null
        if (_sessionState.value != RemoteSessionState.FAILED) {
            _sessionState.value = RemoteSessionState.DISCONNECTED
        }
        if (existing != null) {
            withContext(Dispatchers.IO) { runCatching { existing.disconnect() } }
            onAuditEvent("SSH_DISCONNECT")
        }
    }

    /** Runs one command on the active session with bounded output. */
    suspend fun exec(
        command: String,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
    ): Result<RemoteExecResult> {
        val active = client
        if (active == null || _sessionState.value != RemoteSessionState.CONNECTED) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val result = active.exec(command, timeoutMs, maxOutputChars)
                onAuditEvent("SSH_EXEC exit=${result.exitCode}")
                result
            }
        }
    }

    /** Opens an interactive shell on the active session (terminal backend). */
    fun openShell(cols: Int, rows: Int): SshShellChannel {
        val active = client ?: error("Not connected")
        check(_sessionState.value == RemoteSessionState.CONNECTED) { "Not connected" }
        return active.openShell(cols, rows)
    }

    override suspend fun syncLocalToRemote(localPath: String, remotePath: String): Result<Unit> {
        val active = client
        if (active == null || _sessionState.value != RemoteSessionState.CONNECTED) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        val localRoot = File(localPath)
        if (!localRoot.isDirectory) {
            return Result.failure(IllegalArgumentException("Local path is not a directory: $localPath"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val sftp = active.openSftp()
                try {
                    val engine = SftpSyncEngine(sftp)
                    val local = engine.listLocal(localRoot)
                    val remote = engine.listRemoteRecursive(remotePath)
                    val plan = SyncPlanner.plan(local, remote)
                    val failed = engine.execute(localRoot, remotePath, plan) { progress ->
                        _syncProgress.value = progress
                    }
                    onAuditEvent(
                        "SSH_SYNC files=${plan.items.size} bytes=${plan.totalBytes} failed=${failed.size}"
                    )
                    if (failed.isNotEmpty()) {
                        throw IllegalStateException("Sync failed for: ${failed.joinToString(", ")}")
                    }
                } finally {
                    runCatching { sftp.close() }
                }
            }
        }
    }

    private fun fail(error: Throwable): Result<Unit> {
        _lastFailure.value = error
        _sessionState.value = RemoteSessionState.FAILED
        return Result.failure(error)
    }

    companion object {
        const val DEFAULT_EXEC_TIMEOUT_MS: Long = 120_000L
        const val DEFAULT_MAX_OUTPUT_CHARS: Int = 24_000
        private const val GRACE_MS: Long = 5_000L
    }
}
