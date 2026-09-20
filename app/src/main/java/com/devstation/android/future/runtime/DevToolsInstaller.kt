package com.devstation.android.future.runtime

import com.devstation.android.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class DevToolsStatus(
    val nodeInstalled: Boolean = false,
    val nodeVersion: String? = null,
    val npmInstalled: Boolean = false,
    val npmVersion: String? = null,
    val pythonInstalled: Boolean = false,
    val pythonVersion: String? = null,
    val pipInstalled: Boolean = false,
    val pipVersion: String? = null,
    val gitInstalled: Boolean = false,
    val gitVersion: String? = null
)

/**
 * Automates installing and verifying developer tools (Node.js, npm, Python 3, pip, Git)
 * inside the Linux userspace runtime.
 */
class DevToolsInstaller(
    private val packageManager: LinuxPackageManager,
    private val launcher: LinuxProcessLauncher,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Estimated sizes for the dev tools package bundle.
     */
    val estimatedDownloadBytes = 45_000_000L // ~45 MB compressed
    val estimatedInstalledBytes = 150_000_000L // ~150 MB uncompressed

    val toolPackages = listOf(
        "nodejs",
        "npm",
        "python3",
        "py3-pip",
        "git"
    )

    /**
     * Runs the dev tools installation pipeline.
     */
    fun installDevTools(hostWorkspaceDir: File): Flow<String> = flow {
        emit("Updating package repository indexes...")
        packageManager.updateIndexes(hostWorkspaceDir)

        emit("Installing Node.js, npm, Python 3, pip, and Git (~150 MB)...")
        packageManager.installPackages(toolPackages, hostWorkspaceDir)

        emit("Verifying tool installations...")
        val status = checkToolsStatus(hostWorkspaceDir)

        val summary = StringBuilder("Development Tools Installed:\n")
        summary.append(if (status.nodeInstalled) "✓ Node.js: ${status.nodeVersion}\n" else "✗ Node.js not found\n")
        summary.append(if (status.npmInstalled) "✓ npm: ${status.npmVersion}\n" else "✗ npm not found\n")
        summary.append(if (status.pythonInstalled) "✓ Python: ${status.pythonVersion}\n" else "✗ Python not found\n")
        summary.append(if (status.pipInstalled) "✓ pip: ${status.pipVersion}\n" else "✗ pip not found\n")
        summary.append(if (status.gitInstalled) "✓ Git: ${status.gitVersion}\n" else "✗ Git not found\n")

        emit(summary.toString())
    }.flowOn(dispatchers.io)

    /**
     * Checks versions of installed developer tools.
     */
    suspend fun checkToolsStatus(hostWorkspaceDir: File): DevToolsStatus = withContext(dispatchers.io) {
        val nodeVer = runToolCommand(listOf("node", "--version"), hostWorkspaceDir)
        val npmVer = runToolCommand(listOf("npm", "--version"), hostWorkspaceDir)
        val pyVer = runToolCommand(listOf("python3", "--version"), hostWorkspaceDir)
            ?: runToolCommand(listOf("python", "--version"), hostWorkspaceDir)
        val pipVer = runToolCommand(listOf("pip3", "--version"), hostWorkspaceDir)
            ?: runToolCommand(listOf("pip", "--version"), hostWorkspaceDir)
        val gitVer = runToolCommand(listOf("git", "--version"), hostWorkspaceDir)

        DevToolsStatus(
            nodeInstalled = nodeVer != null,
            nodeVersion = nodeVer?.trim(),
            npmInstalled = npmVer != null,
            npmVersion = npmVer?.trim(),
            pythonInstalled = pyVer != null,
            pythonVersion = pyVer?.trim(),
            pipInstalled = pipVer != null,
            pipVersion = pipVer?.trim(),
            gitInstalled = gitVer != null,
            gitVersion = gitVer?.trim()
        )
    }

    private fun runToolCommand(command: List<String>, hostWorkspaceDir: File): String? {
        return try {
            val proc = launcher.launchProcess(command, hostWorkspaceDir)
            val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
            val output = reader.readLine()
            proc.waitFor()
            output?.trim()
        } catch (e: Exception) {
            null
        }
    }
}
