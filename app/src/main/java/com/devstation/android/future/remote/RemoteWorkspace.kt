package com.devstation.android.future.remote

import kotlinx.coroutines.flow.Flow

data class SshConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val keyAlias: String? = null
)

/**
 * Extension contract for Phase 11 & 12: Remote Workspace, SSH, and Deployment.
 */
interface RemoteWorkspace {
    val isConnected: Flow<Boolean>

    suspend fun connect(config: SshConnectionConfig): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    suspend fun syncLocalToRemote(localPath: String, remotePath: String): Result<Unit>
}

interface DeploymentService {
    suspend fun deploy(projectPath: String, targetEnvironment: String): Result<String>
    suspend fun getDeploymentLogs(deploymentId: String): Flow<String>
}
