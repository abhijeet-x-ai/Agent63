package com.devstation.android.future.runtime

import kotlinx.coroutines.flow.Flow

/**
 * Extension contract for Phase 3: Linux Runtime & Package Manager (PRoot/Container environment).
 */
interface LinuxRuntime {
    val isInitialized: Boolean
    val runtimeStatus: Flow<String>

    suspend fun initializeRootfs(targetDirectory: String): Result<Unit>
    suspend fun executeCommand(command: String, workingDir: String): Result<String>
    suspend fun installPackages(packages: List<String>): Result<Unit>
}
