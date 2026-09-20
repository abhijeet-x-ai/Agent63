package com.devstation.android.future.runtime

import android.content.Context
import com.devstation.android.core.common.DispatcherProvider
import com.devstation.android.core.filesystem.ProjectFileSystemManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress

/**
 * Central manager orchestrating the Linux userspace runtime lifecycle,
 * installation, diagnostics, storage tracking, package management, and terminal sessions.
 */
class LinuxRuntimeManager(
    private val context: Context,
    private val fileSystemManager: ProjectFileSystemManager,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope
) {

    val storagePaths: LinuxStoragePaths by lazy {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val linuxDir = File(baseDir, "linux")
        LinuxStoragePaths(
            linuxRootDir = linuxDir,
            rootfsDir = File(linuxDir, "rootfs"),
            homeDir = File(linuxDir, "home/devstation"),
            downloadsDir = File(linuxDir, "downloads"),
            metadataDir = File(linuxDir, "metadata")
        )
    }

    val environment: LinuxEnvironment by lazy {
        LinuxEnvironment(storagePaths)
    }

    val launcher: LinuxProcessLauncher by lazy {
        LinuxProcessLauncher(storagePaths, environment)
    }

    val installer: LinuxRootfsInstaller by lazy {
        LinuxRootfsInstaller(storagePaths, environment, dispatchers)
    }

    val packageManager: LinuxPackageManager by lazy {
        LinuxPackageManager(launcher, storagePaths, dispatchers)
    }

    val devToolsInstaller: DevToolsInstaller by lazy {
        DevToolsInstaller(packageManager, launcher, dispatchers)
    }

    private val _runtimeState = MutableStateFlow(determineInitialState())
    val runtimeState: StateFlow<LinuxRuntimeState> = _runtimeState.asStateFlow()

    private val _lastDiagnostics = MutableStateFlow<LinuxDiagnostics?>(null)
    val lastDiagnostics: StateFlow<LinuxDiagnostics?> = _lastDiagnostics.asStateFlow()

    /**
     * Checks whether the Linux rootfs is installed and ready for use.
     */
    fun isInstalled(): Boolean {
        val rootfs = storagePaths.rootfsDir
        val binSh = File(rootfs, "bin/sh")
        return rootfs.exists() && binSh.exists()
    }

    private fun determineInitialState(): LinuxRuntimeState {
        return if (isInstalled()) {
            LinuxRuntimeState.READY
        } else {
            LinuxRuntimeState.NOT_INSTALLED
        }
    }

    /**
     * Installs Linux userspace runtime using the appropriate rootfs manifest for device architecture.
     */
    fun install(customManifest: RootfsManifest? = null): Flow<InstallProgress> {
        val arch = CpuArchitectureDetector.detectArchitecture()
        val manifest = customManifest
            ?: RootfsManifestRegistry.getManifestForArchitecture(arch)
            ?: throw IllegalStateException("No verified Linux rootfs manifest available for architecture: ${arch.archString}")

        val workspace = fileSystemManager.defaultWorkspaceDir

        return kotlinx.coroutines.flow.flow {
            installer.installRootfs(manifest, workspace).collect { progress ->
                _runtimeState.value = progress.state
                emit(progress)
            }
        }
    }

    /**
     * Creates a new LinuxTerminalEngine instance.
     */
    fun createTerminalEngine(): LinuxTerminalEngine {
        return LinuxTerminalEngine(launcher, dispatchers, scope)
    }

    /**
     * Runs complete environment diagnostics.
     */
    suspend fun runDiagnostics(hostWorkspaceDir: File = fileSystemManager.defaultWorkspaceDir): LinuxDiagnostics = withContext(dispatchers.io) {
        val arch = CpuArchitectureDetector.detectArchitecture()
        val rootfsDir = storagePaths.rootfsDir
        val homeDir = storagePaths.homeDir
        val shell = File(rootfsDir, "bin/sh")

        val isRootfsOk = rootfsDir.exists() && rootfsDir.isDirectory
        val isHomeOk = homeDir.exists() && homeDir.isDirectory
        val isShellOk = shell.exists() && shell.canExecute()
        val isWorkspaceOk = hostWorkspaceDir.exists() && hostWorkspaceDir.isDirectory

        val isApkOk = packageManager.isAvailable()

        // Tools probe
        val toolsStatus = if (isInstalled()) {
            devToolsInstaller.checkToolsStatus(hostWorkspaceDir)
        } else {
            DevToolsStatus()
        }

        // Internet probe
        val isInternet = try {
            val address = InetAddress.getByName("dl-cdn.alpinelinux.org")
            !address.hostAddress.isNullOrBlank()
        } catch (e: Exception) {
            false
        }

        val ptyAvailable = File("/dev/ptmx").exists()

        val diag = LinuxDiagnostics(
            architectureDetected = arch.archString,
            architectureSupported = arch != CpuArchitecture.UNSUPPORTED,
            isRootfsAvailable = isRootfsOk,
            rootfsDirectory = rootfsDir.absolutePath,
            isHomeAvailable = isHomeOk,
            homeDirectory = homeDir.absolutePath,
            isWorkspaceAvailable = isWorkspaceOk,
            workspaceDirectory = hostWorkspaceDir.absolutePath,
            shellPath = shell.absolutePath,
            isShellAvailable = isShellOk,
            pathConfigured = environment.defaultPath,
            packageManager = "apk",
            isPackageManagerAvailable = isApkOk,
            isNodeInstalled = toolsStatus.nodeInstalled,
            nodeVersion = toolsStatus.nodeVersion,
            isNpmInstalled = toolsStatus.npmInstalled,
            npmVersion = toolsStatus.npmVersion,
            isPythonInstalled = toolsStatus.pythonInstalled,
            pythonVersion = toolsStatus.pythonVersion,
            isPipInstalled = toolsStatus.pipInstalled,
            pipVersion = toolsStatus.pipVersion,
            isGitInstalled = toolsStatus.gitInstalled,
            gitVersion = toolsStatus.gitVersion,
            ptySupported = ptyAvailable,
            ptyStatusMessage = if (ptyAvailable) "Unix /dev/ptmx present" else "PTY unavailable; using Pipe-Bridge fallback",
            isInternetAvailable = isInternet
        )

        _lastDiagnostics.value = diag
        diag
    }

    /**
     * Calculates storage breakdown for the Linux runtime.
     */
    suspend fun calculateLinuxStorage(): LinuxStorageUsage = withContext(dispatchers.io) {
        val rootfsBytes = fileSystemManager.calculateDirectorySize(storagePaths.rootfsDir)
        val homeBytes = fileSystemManager.calculateDirectorySize(storagePaths.homeDir)
        val downloadsBytes = fileSystemManager.calculateDirectorySize(storagePaths.downloadsDir)
        val totalBytes = rootfsBytes + homeBytes + downloadsBytes

        LinuxStorageUsage(
            rootfsBytes = rootfsBytes,
            homeBytes = homeBytes,
            downloadsBytes = downloadsBytes,
            totalLinuxBytes = totalBytes
        )
    }

    /**
     * Resets the Linux environment by clearing the rootfs.
     * CRITICAL SECURITY INVARIANT: NEVER touches DevStation user projects in /projects.
     */
    suspend fun resetEnvironment(keepHome: Boolean = true): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            _runtimeState.value = LinuxRuntimeState.REMOVING

            // Clear rootfs
            if (storagePaths.rootfsDir.exists()) {
                storagePaths.rootfsDir.deleteRecursively()
            }
            if (storagePaths.downloadsDir.exists()) {
                storagePaths.downloadsDir.deleteRecursively()
            }

            // Optionally clear persistent home
            if (!keepHome && storagePaths.homeDir.exists()) {
                storagePaths.homeDir.deleteRecursively()
            }

            _runtimeState.value = LinuxRuntimeState.NOT_INSTALLED
        }
    }

    /**
     * Uninstalls the Linux environment completely.
     * CRITICAL SECURITY INVARIANT: NEVER touches DevStation user projects in /projects.
     */
    suspend fun uninstallLinux(): Result<Unit> = withContext(dispatchers.io) {
        runCatching {
            _runtimeState.value = LinuxRuntimeState.REMOVING

            if (storagePaths.linuxRootDir.exists()) {
                storagePaths.linuxRootDir.deleteRecursively()
            }

            _runtimeState.value = LinuxRuntimeState.NOT_INSTALLED
        }
    }
}

data class LinuxStorageUsage(
    val rootfsBytes: Long = 0L,
    val homeBytes: Long = 0L,
    val downloadsBytes: Long = 0L,
    val totalLinuxBytes: Long = 0L
)
