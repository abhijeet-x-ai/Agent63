package com.devstation.android.future.runtime

import java.io.File

/**
 * Lifecycle state of the local Linux userspace runtime.
 */
enum class LinuxRuntimeState {
    NOT_INSTALLED,
    CHECKING_STORAGE,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    CONFIGURING,
    BOOTSTRAPPING,
    READY,
    FAILED,
    CANCELLED,
    UPDATING,
    REMOVING
}

/**
 * Supported CPU Architectures for Linux rootfs.
 */
enum class CpuArchitecture(val archString: String) {
    ARM64("aarch64"),
    X86_64("x86_64"),
    ARM32("armv7"),
    X86("x86"),
    UNSUPPORTED("unsupported");

    companion object {
        fun fromString(str: String): CpuArchitecture {
            val lower = str.lowercase()
            return when {
                lower.contains("aarch64") || lower.contains("arm64") -> ARM64
                lower.contains("x86_64") || lower.contains("amd64") -> X86_64
                lower.contains("arm") -> ARM32
                lower.contains("x86") || lower.contains("i386") || lower.contains("i686") -> X86
                else -> UNSUPPORTED
            }
        }
    }
}

/**
 * Supported Linux distributions for userspace runtime.
 */
enum class LinuxDistro(val displayName: String, val packageManagerName: String) {
    ALPINE("Alpine Linux", "apk"),
    DEBIAN("Debian GNU/Linux", "apt"),
    UBUNTU("Ubuntu", "apt")
}

/**
 * Terminal runtime backend selection.
 */
enum class RuntimeType(val displayName: String) {
    ANDROID_SHELL("Android Shell"),
    LINUX_USERSPACE("Linux Environment"),
    REMOTE("Remote (Coming Soon)")
}

/**
 * Manifest definition for an official Linux rootfs distribution archive.
 */
data class RootfsManifest(
    val distro: LinuxDistro,
    val version: String,
    val architecture: CpuArchitecture,
    val downloadUrl: String,
    val checksumSha256: String,
    val archiveSizeBytes: Long,
    val estimatedInstalledSizeBytes: Long,
    val sourceLicense: String = "GPL-2.0 / MIT / BSD"
)

/**
 * Progress updates during download and extraction.
 */
data class InstallProgress(
    val state: LinuxRuntimeState,
    val stepTitle: String,
    val currentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressPercentage: Int = 0,
    val detailMessage: String = ""
)

/**
 * Diagnostics and environment report for the Linux runtime.
 */
data class LinuxDiagnostics(
    val architectureDetected: String,
    val architectureSupported: Boolean,
    val isRootfsAvailable: Boolean,
    val rootfsDirectory: String,
    val isHomeAvailable: Boolean,
    val homeDirectory: String,
    val isWorkspaceAvailable: Boolean,
    val workspaceDirectory: String,
    val shellPath: String,
    val isShellAvailable: Boolean,
    val pathConfigured: String,
    val packageManager: String,
    val isPackageManagerAvailable: Boolean,
    val isNodeInstalled: Boolean,
    val nodeVersion: String? = null,
    val isNpmInstalled: Boolean,
    val npmVersion: String? = null,
    val isPythonInstalled: Boolean,
    val pythonVersion: String? = null,
    val isPipInstalled: Boolean,
    val pipVersion: String? = null,
    val isGitInstalled: Boolean,
    val gitVersion: String? = null,
    val ptySupported: Boolean = false,
    val ptyStatusMessage: String = "Full Unix PTY not available; using Pipe-Bridge fallback",
    val isInternetAvailable: Boolean = false
)

/**
 * Information on an installed or available package.
 */
data class PackageInfo(
    val name: String,
    val version: String,
    val description: String = "",
    val isInstalled: Boolean = false
)

/**
 * Package manager operational status.
 */
data class PackageManagerStatus(
    val name: String,
    val isReady: Boolean,
    val lastUpdatedTimestamp: Long = 0L,
    val installedPackageCount: Int = 0
)

/**
 * Paths configuration for the Linux userspace layout.
 */
data class LinuxStoragePaths(
    val linuxRootDir: File,
    val rootfsDir: File,
    val homeDir: File,
    val downloadsDir: File,
    val metadataDir: File
)
