package com.devstation.android.future.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsManifestTest {

    @Test
    fun `official manifests exist for ARM64 and X86_64`() {
        val armManifest = RootfsManifestRegistry.getManifestForArchitecture(CpuArchitecture.ARM64)
        assertNotNull(armManifest)
        assertEquals(LinuxDistro.ALPINE, armManifest!!.distro)
        assertTrue(armManifest.downloadUrl.startsWith("https://"))
        assertEquals(64, armManifest.checksumSha256.length)

        val x86Manifest = RootfsManifestRegistry.getManifestForArchitecture(CpuArchitecture.X86_64)
        assertNotNull(x86Manifest)
        assertEquals(LinuxDistro.ALPINE, x86Manifest!!.distro)
        assertTrue(x86Manifest.downloadUrl.startsWith("https://"))
        assertEquals(64, x86Manifest.checksumSha256.length)
    }

    @Test
    fun `custom manifest override replaces registry lookup`() {
        val custom = RootfsManifest(
            distro = LinuxDistro.DEBIAN,
            version = "12",
            architecture = CpuArchitecture.ARM64,
            downloadUrl = "https://custom.mirror/rootfs.tar.gz",
            checksumSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            archiveSizeBytes = 1000L,
            estimatedInstalledSizeBytes = 5000L
        )

        try {
            RootfsManifestRegistry.setCustomManifestOverride(custom)
            val retrieved = RootfsManifestRegistry.getManifestForArchitecture(CpuArchitecture.ARM64)
            assertEquals(custom, retrieved)
        } finally {
            RootfsManifestRegistry.setCustomManifestOverride(null)
        }
    }
}
