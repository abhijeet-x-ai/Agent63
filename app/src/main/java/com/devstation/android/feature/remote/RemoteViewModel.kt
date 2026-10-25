package com.devstation.android.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import com.devstation.android.core.remote.HostKeyChangedException
import com.devstation.android.core.remote.HostKeyUnknownException
import com.devstation.android.core.remote.RemoteSessionState
import com.devstation.android.core.remote.SshAuthType
import com.devstation.android.core.remote.SshCredentialManager
import com.devstation.android.core.remote.SshHostProfile
import com.devstation.android.core.remote.SshHostRepository
import com.devstation.android.core.remote.SshSessionManager
import com.devstation.android.core.remote.SshTerminalEngine
import com.devstation.android.core.remote.SyncProgress
import com.devstation.android.core.remote.validateSshHostInput
import com.devstation.android.future.runtime.RuntimeType
import com.devstation.android.future.terminal.TerminalManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A first-connect host key awaiting the user's trust decision. */
data class TrustRequest(
    val profileId: String,
    val profileName: String,
    val fingerprint: String,
    val keyType: String
)

/** A changed host key (possible MITM, or a reinstalled server). */
data class ChangedKeyWarning(
    val profileId: String,
    val profileName: String,
    val storedFingerprint: String,
    val presentedFingerprint: String
)

data class RemoteUiState(
    val profiles: List<SshHostProfile> = emptyList(),
    val sessionState: RemoteSessionState = RemoteSessionState.DISCONNECTED,
    val activeProfileId: String? = null,
    val busyProfileId: String? = null,
    val trustRequest: TrustRequest? = null,
    val changedKeyWarning: ChangedKeyWarning? = null,
    val syncProgress: SyncProgress? = null,
    val isSyncing: Boolean = false,
    val userMessage: String? = null
)

/** Raw editor form contents; blank secret fields on edit mean "keep existing". */
data class HostFormInput(
    val id: String?,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: SshAuthType,
    val password: String,
    val privateKeyPem: String,
    val keyPassphrase: String
)

class RemoteViewModel(
    private val repository: SshHostRepository,
    private val credentials: SshCredentialManager,
    private val sessionManager: SshSessionManager,
    private val terminalManager: TerminalManager,
    private val workspaceDir: File
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    val defaultLocalPath: String get() = workspaceDir.absolutePath

    init {
        viewModelScope.launch {
            repository.observeProfiles().collect { profiles ->
                _uiState.update { it.copy(profiles = profiles) }
            }
        }
        viewModelScope.launch {
            sessionManager.sessionState.collect { state ->
                _uiState.update { it.copy(sessionState = state) }
            }
        }
        viewModelScope.launch {
            sessionManager.activeProfile.collect { profile ->
                _uiState.update { it.copy(activeProfileId = profile?.id) }
            }
        }
        viewModelScope.launch {
            sessionManager.syncProgress.collect { progress ->
                _uiState.update { it.copy(syncProgress = progress) }
            }
        }
    }

    fun profileById(id: String?): SshHostProfile? =
        _uiState.value.profiles.firstOrNull { it.id == id }

    fun hasSecret(profile: SshHostProfile): Boolean =
        credentials.hasSecret(profile.id, profile.authType)

    fun connect(profile: SshHostProfile) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(busyProfileId = profile.id, trustRequest = null, changedKeyWarning = null)
            }
            val result = sessionManager.connectProfile(profile.id)
            _uiState.update { it.copy(busyProfileId = null) }
            result.onFailure { error -> surfaceConnectFailure(profile, error) }
        }
    }

    private fun surfaceConnectFailure(profile: SshHostProfile, error: Throwable) {
        when (error) {
            is HostKeyUnknownException -> _uiState.update {
                it.copy(
                    trustRequest = TrustRequest(profile.id, profile.name, error.fingerprint, error.keyType)
                )
            }
            is HostKeyChangedException -> _uiState.update {
                it.copy(
                    changedKeyWarning = ChangedKeyWarning(
                        profile.id, profile.name, error.storedFingerprint, error.presentedFingerprint
                    )
                )
            }
            else -> _uiState.update {
                it.copy(userMessage = "Connection failed: ${error.message ?: "unknown error"}")
            }
        }
    }

    /** User trusted the presented key: persist it and reconnect. */
    fun confirmTrust() {
        val request = _uiState.value.trustRequest ?: return
        viewModelScope.launch {
            repository.trustHostKey(request.profileId, request.fingerprint, request.keyType)
            _uiState.update { it.copy(trustRequest = null) }
            profileById(request.profileId)?.let { connect(it) }
        }
    }

    fun dismissTrust() {
        _uiState.update { it.copy(trustRequest = null, userMessage = "Connection cancelled") }
    }

    /** User accepted a changed key (e.g. server was reinstalled): replace and reconnect. */
    fun confirmChangedKey() {
        val warning = _uiState.value.changedKeyWarning ?: return
        viewModelScope.launch {
            repository.trustHostKey(warning.profileId, warning.presentedFingerprint, null)
            _uiState.update { it.copy(changedKeyWarning = null) }
            profileById(warning.profileId)?.let { connect(it) }
        }
    }

    fun dismissChangedKey() {
        _uiState.update { it.copy(changedKeyWarning = null, userMessage = "Connection blocked: host key mismatch") }
    }

    fun disconnect() {
        viewModelScope.launch { sessionManager.disconnect() }
    }

    fun deleteHost(profile: SshHostProfile) {
        viewModelScope.launch {
            if (_uiState.value.activeProfileId == profile.id) sessionManager.disconnect()
            repository.delete(profile.id)
            _uiState.update { it.copy(userMessage = "Deleted ${profile.name}") }
        }
    }

    /** Creates a terminal tab bound to the SSH shell and reports when it is ready. */
    fun openTerminal(profile: SshHostProfile, onOpened: () -> Unit) {
        viewModelScope.launch {
            try {
                terminalManager.createSession(
                    workingDir = workspaceDir.absolutePath,
                    title = "SSH · ${profile.name}",
                    runtimeType = RuntimeType.REMOTE,
                    customEngine = SshTerminalEngine(sessionManager, profile)
                )
                onOpened()
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(userMessage = "Could not open remote terminal: ${t.message ?: "unknown error"}")
                }
            }
        }
    }

    fun sync(profile: SshHostProfile, localPath: String, remotePath: String) {
        if (_uiState.value.activeProfileId != profile.id) {
            _uiState.update { it.copy(userMessage = "Connect to ${profile.name} first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = sessionManager.syncLocalToRemote(localPath, remotePath)
            _uiState.update { it.copy(isSyncing = false) }
            result.fold(
                onSuccess = { _uiState.update { it.copy(userMessage = "Sync complete") } },
                onFailure = { error ->
                    _uiState.update { it.copy(userMessage = "Sync failed: ${error.message ?: "unknown error"}") }
                }
            )
        }
    }

    fun saveHost(input: HostFormInput, onSaved: () -> Unit) {
        val problems = validateSshHostInput(input.name, input.host, input.port, input.username)
        if (problems.isNotEmpty()) {
            _uiState.update { it.copy(userMessage = problems.joinToString("\n")) }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = input.id?.let { repository.profile(it) }
            val id = existing?.id ?: UUID.randomUUID().toString()
            val targetChanged = existing != null &&
                (existing.host != input.host.trim() || existing.port != input.port)
            val profile = SshHostProfile(
                id = id,
                name = input.name.trim(),
                host = input.host.trim(),
                port = input.port,
                username = input.username.trim(),
                authType = input.authType,
                hostKeyFingerprint = if (targetChanged) null else existing?.hostKeyFingerprint,
                hostKeyType = if (targetChanged) null else existing?.hostKeyType,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastConnectedAt = existing?.lastConnectedAt
            )
            repository.save(profile)
            when (input.authType) {
                SshAuthType.PASSWORD -> {
                    if (input.password.isNotBlank()) {
                        credentials.savePassword(id, input.password)
                    }
                }
                SshAuthType.PRIVATE_KEY -> {
                    if (input.privateKeyPem.isNotBlank()) {
                        credentials.savePrivateKey(
                            id,
                            input.privateKeyPem.trim(),
                            input.keyPassphrase.ifBlank { null }
                        )
                    }
                }
            }
            _uiState.update { it.copy(userMessage = "Saved ${profile.name}") }
            onSaved()
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    companion object {
        fun provideFactory(
            repository: SshHostRepository,
            credentials: SshCredentialManager,
            sessionManager: SshSessionManager,
            terminalManager: TerminalManager,
            fileSystemManager: ProjectFileSystemManager
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RemoteViewModel(
                    repository = repository,
                    credentials = credentials,
                    sessionManager = sessionManager,
                    terminalManager = terminalManager,
                    workspaceDir = fileSystemManager.defaultWorkspaceDir
                ) as T
            }
        }
    }
}
