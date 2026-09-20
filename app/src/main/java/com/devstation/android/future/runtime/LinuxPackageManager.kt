package com.devstation.android.future.runtime

import com.devstation.android.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Manages package installation, removal, and queries inside the Linux userspace runtime.
 * Implements Alpine Linux `apk` integration with fallbacks.
 */
class LinuxPackageManager(
    private val launcher: LinuxProcessLauncher,
    private val storagePaths: LinuxStoragePaths,
    private val dispatchers: DispatcherProvider
) {

    val name: String = "apk"

    /**
     * Checks if the package manager binary exists in the rootfs.
     */
    fun isAvailable(): Boolean {
        val apkBin = File(storagePaths.rootfsDir, "sbin/apk")
        val apkBin2 = File(storagePaths.rootfsDir, "bin/apk")
        return apkBin.exists() || apkBin2.exists()
    }

    /**
     * Updates package repositories inside the Linux runtime (`apk update`).
     */
    suspend fun updateIndexes(hostWorkspaceDir: File): Result<String> = withContext(dispatchers.io) {
        runCatching {
            executeApkCommand(listOf("apk", "update"), hostWorkspaceDir)
        }
    }

    /**
     * Installs one or more packages (`apk add --no-cache <packages>`).
     */
    suspend fun installPackages(packages: List<String>, hostWorkspaceDir: File): Result<String> = withContext(dispatchers.io) {
        runCatching {
            require(packages.isNotEmpty()) { "Package list cannot be empty" }
            val cmd = listOf("apk", "add", "--no-cache") + packages
            executeApkCommand(cmd, hostWorkspaceDir)
        }
    }

    /**
     * Removes a package (`apk del <package>`).
     */
    suspend fun removePackage(packageName: String, hostWorkspaceDir: File): Result<String> = withContext(dispatchers.io) {
        runCatching {
            require(packageName.isNotBlank()) { "Package name cannot be blank" }
            val cmd = listOf("apk", "del", packageName.trim())
            executeApkCommand(cmd, hostWorkspaceDir)
        }
    }

    /**
     * Lists currently installed packages (`apk info`).
     */
    suspend fun listInstalledPackages(hostWorkspaceDir: File): Result<List<String>> = withContext(dispatchers.io) {
        runCatching {
            val output = executeApkCommand(listOf("apk", "info"), hostWorkspaceDir)
            output.lines().map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    /**
     * Searches for packages by pattern (`apk search <query>`).
     */
    suspend fun searchPackages(query: String, hostWorkspaceDir: File): Result<List<String>> = withContext(dispatchers.io) {
        runCatching {
            val output = executeApkCommand(listOf("apk", "search", query.trim()), hostWorkspaceDir)
            output.lines().map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    /**
     * Checks if a package is installed.
     */
    suspend fun isPackageInstalled(packageName: String, hostWorkspaceDir: File): Boolean = withContext(dispatchers.io) {
        val result = runCatching {
            executeApkCommand(listOf("apk", "info", "-e", packageName.trim()), hostWorkspaceDir)
        }
        result.isSuccess && result.getOrNull()?.contains(packageName.trim()) == true
    }

    private fun executeApkCommand(command: List<String>, hostWorkspaceDir: File): String {
        val proc = launcher.launchProcess(command, hostWorkspaceDir)
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outReader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val errReader = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))

        var line: String?
        while (outReader.readLine().also { line = it } != null) {
            stdout.append(line).append('\n')
        }
        while (errReader.readLine().also { line = it } != null) {
            stderr.append(line).append('\n')
        }

        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val errMessage = stderr.toString().ifBlank { stdout.toString() }
            throw IllegalStateException("Package manager exited with code $exitCode: $errMessage")
        }

        return stdout.toString()
    }
}
