package com.devstation.android.future.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuArchitectureDetectorTest {

    @Test
    fun `detectArchitecture matches arm64-v8a correctly`() {
        val arch = CpuArchitectureDetector.detectArchitecture(arrayOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(CpuArchitecture.ARM64, arch)
        assertEquals("aarch64", arch.archString)
    }

    @Test
    fun `detectArchitecture matches x86_64 correctly`() {
        val arch = CpuArchitectureDetector.detectArchitecture(arrayOf("x86_64"))
        assertEquals(CpuArchitecture.X86_64, arch)
        assertEquals("x86_64", arch.archString)
    }

    @Test
    fun `detectArchitecture matches arm32 correctly`() {
        val arch = CpuArchitectureDetector.detectArchitecture(arrayOf("armeabi-v7a", "armeabi"))
        assertEquals(CpuArchitecture.ARM32, arch)
    }

    @Test
    fun `detectArchitecture returns unsupported for unknown ABI`() {
        val arch = CpuArchitectureDetector.detectArchitecture(arrayOf("mips64", "riscv"))
        assertEquals(CpuArchitecture.UNSUPPORTED, arch)
    }
}
