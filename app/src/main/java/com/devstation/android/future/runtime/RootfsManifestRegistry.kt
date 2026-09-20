package com.devstation.android.future.runtime

/**
 * Registry of official Linux rootfs manifests.
 * Uses official, verified Alpine Linux 3.19 releases.
 */
object RootfsManifestRegistry {

    private val officialManifests = mapOf(
        CpuArchitecture.ARM64 to RootfsManifest(
            distro = LinuxDistro.ALPINE,
            version = "3.19.1",
            architecture = CpuArchitecture.ARM64,
            downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            checksumSha256 = "293e5076f7b9f39002931a7bcbe4b22c713b1be457a41434c4f346a066929cf0",
            archiveSizeBytes = 3_628_000L,
            estimatedInstalledSizeBytes = 12_500_000L,
            sourceLicense = "Alpine Linux (GPL-2.0 / MIT / BSD components)"
        ),
        CpuArchitecture.X86_64 to RootfsManifest(
            distro = LinuxDistro.ALPINE,
            version = "3.19.1",
            architecture = CpuArchitecture.X86_64,
            downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-3.19.1-x86_64.tar.gz",
            checksumSha256 = "9e2d31fefd824d52e5d9c2a3821ca2b069d2a6a1aaec9f46b4162e086f6d54cf",
            archiveSizeBytes = 3_820_000L,
            estimatedInstalledSizeBytes = 13_200_000L,
            sourceLicense = "Alpine Linux (GPL-2.0 / MIT / BSD components)"
        )
    )

    private var customManifestOverride: RootfsManifest? = null

    /**
     * Resolves the rootfs manifest for the specified architecture.
     */
    fun getManifestForArchitecture(architecture: CpuArchitecture): RootfsManifest? {
        if (customManifestOverride != null && customManifestOverride?.architecture == architecture) {
            return customManifestOverride
        }
        return officialManifests[architecture]
    }

    /**
     * Allows test fixtures or custom mirror configurations to provide a custom manifest.
     */
    fun setCustomManifestOverride(manifest: RootfsManifest?) {
        customManifestOverride = manifest
    }
}
