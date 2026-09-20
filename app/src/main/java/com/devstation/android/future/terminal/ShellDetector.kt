package com.devstation.android.future.terminal

import java.io.File

object ShellDetector {

    private val androidCandidates = listOf(
        "/system/bin/sh" to "Android sh",
        "/system/bin/mksh" to "Android mksh",
        "/system/xbin/sh" to "Android sh",
        "/bin/sh" to "POSIX sh",
        "/bin/bash" to "bash"
    )

    fun detectShell(): ShellInfo {
        for ((path, name) in androidCandidates) {
            val file = File(path)
            if (file.exists() && (file.canExecute() || file.isFile)) {
                return ShellInfo(
                    name = name,
                    path = file.absolutePath,
                    isAvailable = true,
                    ptySupported = false
                )
            }
        }

        // On non-Android JVM environments (e.g. Windows unit tests)
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        if (os.contains("win")) {
            val cmd = System.getenv("ComSpec") ?: "cmd.exe"
            return ShellInfo(
                name = "Windows CMD",
                path = cmd,
                isAvailable = File(cmd).exists() || cmd.isNotBlank(),
                ptySupported = false
            )
        }

        // Generic fallback to "sh" executable found in PATH
        return ShellInfo(
            name = "sh",
            path = "sh",
            isAvailable = true,
            ptySupported = false
        )
    }
}
