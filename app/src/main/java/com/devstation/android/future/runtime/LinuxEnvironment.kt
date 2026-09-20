package com.devstation.android.future.runtime

import java.io.File

/**
 * Manages environment variables and filesystem layout for the Linux userspace runtime.
 */
class LinuxEnvironment(
    val storagePaths: LinuxStoragePaths
) {

    val defaultPath: String = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    val defaultHome: String = "/home/devstation"
    val defaultWorkspace: String = "/workspace"

    /**
     * Builds the environment variables map for the Linux userspace process.
     */
    fun buildEnvironment(
        customVars: Map<String, String> = emptyMap(),
        activeWorkspaceDir: String = defaultWorkspace
    ): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["HOME"] = defaultHome
        env["PATH"] = defaultPath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        env["LC_ALL"] = "en_US.UTF-8"
        env["USER"] = "devstation"
        env["LOGNAME"] = "devstation"
        env["SHELL"] = "/bin/sh"
        env["PWD"] = activeWorkspaceDir

        // Apply custom variables without overriding core security invariants
        for ((key, value) in customVars) {
            if (key != "LD_PRELOAD" && key != "ANDROID_ROOT") {
                env[key] = value
            }
        }

        return env
    }

    /**
     * Initializes initial Linux configuration files in the rootfs if missing.
     * Sets up /etc/resolv.conf for DNS, /home/devstation, and /workspace.
     */
    fun bootstrapFilesystem(projectWorkspaceDir: File) {
        val rootfs = storagePaths.rootfsDir
        val etcDir = File(rootfs, "etc")
        if (!etcDir.exists()) etcDir.mkdirs()

        // Configure DNS nameservers
        val resolvConf = File(etcDir, "resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText(
                """
                nameserver 8.8.8.8
                nameserver 1.1.1.1
                """.trimIndent() + "\n"
            )
        }

        // Configure persistent Linux user home
        val homeDir = storagePaths.homeDir
        if (!homeDir.exists()) homeDir.mkdirs()

        // Create initial ~/.profile in Linux home if not present
        val profile = File(homeDir, ".profile")
        if (!profile.exists()) {
            profile.writeText(
                """
                # DevStation Linux Environment Profile
                export PATH="$defaultPath"
                export HOME="$defaultHome"
                export TERM="xterm-256color"
                export LANG="en_US.UTF-8"
                alias ll='ls -la'
                alias l='ls -l'
                cd /workspace 2>/dev/null || cd ~
                """.trimIndent() + "\n"
            )
        }

        // Create /workspace mountpoint inside rootfs
        val workspaceMountpoint = File(rootfs, "workspace")
        if (!workspaceMountpoint.exists()) {
            workspaceMountpoint.mkdirs()
        }

        // Create /home/devstation mountpoint inside rootfs
        val homeMountpoint = File(rootfs, "home/devstation")
        if (!homeMountpoint.exists()) {
            homeMountpoint.mkdirs()
        }

        // Create /tmp directory with read/write
        val tmpDir = File(rootfs, "tmp")
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
        }
        tmpDir.setReadable(true, false)
        tmpDir.setWritable(true, false)
    }
}
