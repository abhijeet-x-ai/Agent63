package com.devstation.android.future.runtime

import android.os.Build

/**
 * Detects device CPU architecture and matches it to a supported Linux userspace ABI.
 */
object CpuArchitectureDetector {

    /**
     * Detects CPU architecture from Android Build properties or falls back to system properties.
     */
    fun detectArchitecture(abis: Array<String> = getSupportedAbis()): CpuArchitecture {
        if (abis.isNotEmpty()) {
            for (abi in abis) {
                val normalized = abi.lowercase()
                when {
                    normalized.contains("arm64") || normalized.contains("aarch64") -> {
                        return CpuArchitecture.ARM64
                    }
                    normalized.contains("x86_64") || normalized.contains("amd64") -> {
                        return CpuArchitecture.X86_64
                    }
                    normalized.contains("armeabi-v7a") || normalized.contains("armv7") -> {
                        return CpuArchitecture.ARM32
                    }
                    normalized.contains("x86") -> {
                        return CpuArchitecture.X86
                    }
                }
            }
            return CpuArchitecture.UNSUPPORTED
        }

        // Fallback check on os.arch
        val osArch = System.getProperty("os.arch") ?: ""
        return CpuArchitecture.fromString(osArch)
    }

    private fun getSupportedAbis(): Array<String> {
        return try {
            Build.SUPPORTED_ABIS ?: emptyArray()
        } catch (e: Throwable) {
            val osArch = System.getProperty("os.arch") ?: ""
            arrayOf(osArch)
        }
    }
}
