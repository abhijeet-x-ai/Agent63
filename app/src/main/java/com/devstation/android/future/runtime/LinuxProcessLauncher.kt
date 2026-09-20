package com.devstation.android.future.runtime

import java.io.File

/**
 * Builds and launches unprivileged Linux userspace processes using PRoot
 * or compatible userspace bridge.
 */
class LinuxProcessLauncher(
    private val storagePaths: LinuxStoragePaths,
    private val environmentBuilder: LinuxEnvironment,
    private val prootBinaryPath: String? = null
) {

    /**
     * Checks if PRoot binary is available and executable.
     */
    fun isProotAvailable(): Boolean {
        val bin = findProotBinary()
        return bin != null && bin.exists() && bin.canExecute()
    }

    /**
     * Locates PRoot binary in known app locations.
     */
    fun findProotBinary(): File? {
        if (!prootBinaryPath.isNullOrBlank()) {
            val f = File(prootBinaryPath)
            if (f.exists()) return f
        }

        // Check in linux/bin/proot
        val customBin = File(storagePaths.linuxRootDir, "bin/proot")
        if (customBin.exists()) return customBin

        return null
    }

    /**
     * Builds the complete command list to execute an interactive shell or command inside Linux.
     *
     * @param guestCommand Command to run inside Linux (defaults to "/bin/sh").
     * @param hostWorkspaceDir DevStation project folder on the host to bind to /workspace.
     * @param workingDir Directory inside Linux guest to start in (default: "/workspace").
     */
    fun buildLaunchCommand(
        guestCommand: List<String> = listOf("/bin/sh"),
        hostWorkspaceDir: File,
        workingDir: String = "/workspace"
    ): List<String> {
        val rootfsDir = storagePaths.rootfsDir
        val homeDir = storagePaths.homeDir

        val prootBin = findProotBinary()

        if (prootBin != null && prootBin.exists()) {
            val cmd = mutableListOf<String>()
            cmd.add(prootBin.absolutePath)
            cmd.add("-r")
            cmd.add(rootfsDir.absolutePath)
            cmd.add("-0") // Fake root privileges inside guest

            // Bind mounts
            if (hostWorkspaceDir.exists()) {
                cmd.add("-b")
                cmd.add("${hostWorkspaceDir.canonicalPath}:/workspace")
            }

            if (homeDir.exists()) {
                cmd.add("-b")
                cmd.add("${homeDir.canonicalPath}:/home/devstation")
            }

            // Standard pseudo-filesystems
            if (File("/dev").exists()) {
                cmd.add("-b")
                cmd.add("/dev")
            }
            if (File("/proc").exists()) {
                cmd.add("-b")
                cmd.add("/proc")
            }
            if (File("/sys").exists()) {
                cmd.add("-b")
                cmd.add("/sys")
            }

            cmd.add("-w")
            cmd.add(workingDir)

            cmd.addAll(guestCommand)
            return cmd
        } else {
            // Fallback / direct chroot execution bridge for environments where PRoot binary is not yet compiled
            // Uses standard system shell with rootfs context
            val fallbackShell = File(rootfsDir, "bin/sh")
            return if (fallbackShell.exists()) {
                listOf(fallbackShell.absolutePath) + guestCommand.drop(1)
            } else {
                listOf("/system/bin/sh", "-c", guestCommand.joinToString(" "))
            }
        }
    }

    /**
     * Spawns a Process configured with Linux environment variables and working directory.
     */
    fun launchProcess(
        command: List<String>,
        hostWorkspaceDir: File,
        customEnv: Map<String, String> = emptyMap()
    ): Process {
        val fullCommand = buildLaunchCommand(command, hostWorkspaceDir)
        val builder = ProcessBuilder(fullCommand)

        // Set host working directory
        if (hostWorkspaceDir.exists()) {
            builder.directory(hostWorkspaceDir)
        } else {
            builder.directory(storagePaths.rootfsDir)
        }

        // Set guest environment variables
        val env = builder.environment()
        val linuxEnv = environmentBuilder.buildEnvironment(customEnv)
        for ((k, v) in linuxEnv) {
            env[k] = v
        }

        return builder.start()
    }
}
