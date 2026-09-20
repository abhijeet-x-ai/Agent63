package com.devstation.android.future.runtime

import android.os.StatFs
import com.devstation.android.core.common.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads, verifies, and installs the Linux root filesystem.
 */
class LinuxRootfsInstaller(
    private val storagePaths: LinuxStoragePaths,
    private val environmentBuilder: LinuxEnvironment,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Installs the specified Linux rootfs manifest.
     * Emits granular progress updates through Flow.
     */
    fun installRootfs(
        manifest: RootfsManifest,
        targetProjectWorkspaceDir: File
    ): Flow<InstallProgress> = channelFlow {
        // Step 1: Check storage
        send(
            InstallProgress(
                state = LinuxRuntimeState.CHECKING_STORAGE,
                stepTitle = "Checking storage space",
                detailMessage = "Verifying sufficient free space on device..."
            )
        )

        val requiredBytes = manifest.archiveSizeBytes + manifest.estimatedInstalledSizeBytes + (20 * 1024 * 1024L)
        val availableBytes = getAvailableStorageBytes(storagePaths.linuxRootDir)
        if (availableBytes > 0 && availableBytes < requiredBytes) {
            val reqMb = requiredBytes / (1024 * 1024)
            val availMb = availableBytes / (1024 * 1024)
            throw IllegalStateException("Insufficient storage space: $reqMb MB required, but only $availMb MB available.")
        }

        // Prepare directories
        storagePaths.downloadsDir.mkdirs()
        storagePaths.rootfsDir.mkdirs()
        storagePaths.homeDir.mkdirs()
        storagePaths.metadataDir.mkdirs()

        val downloadFile = File(
            storagePaths.downloadsDir,
            "${manifest.distro.name.lowercase()}-${manifest.version}-${manifest.architecture.archString}.tar.gz"
        )

        // Step 2: Download rootfs archive (if not already downloaded & verified)
        val needDownload = !downloadFile.exists() || downloadFile.length() == 0L || !verifyChecksum(downloadFile, manifest.checksumSha256)
        if (needDownload) {
            send(
                InstallProgress(
                    state = LinuxRuntimeState.DOWNLOADING,
                    stepTitle = "Downloading ${manifest.distro.displayName}",
                    totalBytes = manifest.archiveSizeBytes,
                    detailMessage = "Connecting to official distribution repository..."
                )
            )

            downloadArchive(manifest.downloadUrl, downloadFile) { currentBytes, totalBytes, percent ->
                trySend(
                    InstallProgress(
                        state = LinuxRuntimeState.DOWNLOADING,
                        stepTitle = "Downloading ${manifest.distro.displayName}",
                        currentBytes = currentBytes,
                        totalBytes = if (totalBytes > 0) totalBytes else manifest.archiveSizeBytes,
                        progressPercentage = percent,
                        detailMessage = "${currentBytes / 1024} KB / ${manifest.archiveSizeBytes / 1024} KB"
                    )
                )
            }
        }

        // Step 3: Verify Checksum
        send(
            InstallProgress(
                state = LinuxRuntimeState.VERIFYING,
                stepTitle = "Verifying archive integrity",
                detailMessage = "Computing and matching SHA-256 checksum..."
            )
        )

        val isValid = verifyChecksum(downloadFile, manifest.checksumSha256)
        if (!isValid) {
            downloadFile.delete()
            throw IllegalStateException("Checksum verification failed for rootfs archive. Corrupt download removed.")
        }

        // Step 4: Extract root filesystem
        send(
            InstallProgress(
                state = LinuxRuntimeState.EXTRACTING,
                stepTitle = "Extracting Linux filesystem",
                detailMessage = "Extracting rootfs safely into app sandbox..."
            )
        )

        FileInputStream(downloadFile).use { fileIn ->
            TarExtractor.extract(
                archiveStream = fileIn,
                destinationDir = storagePaths.rootfsDir,
                isGzipped = true
            ) { filesCount, bytesProcessed ->
                trySend(
                    InstallProgress(
                        state = LinuxRuntimeState.EXTRACTING,
                        stepTitle = "Extracting Linux filesystem",
                        currentBytes = bytesProcessed,
                        totalBytes = manifest.estimatedInstalledSizeBytes,
                        progressPercentage = ((bytesProcessed.toFloat() / manifest.estimatedInstalledSizeBytes) * 100).toInt().coerceIn(0, 100),
                        detailMessage = "Extracted $filesCount files..."
                    )
                )
            }
        }

        // Step 5: Configure runtime & bootstrap filesystem
        send(
            InstallProgress(
                state = LinuxRuntimeState.CONFIGURING,
                stepTitle = "Configuring Linux runtime",
                detailMessage = "Setting up /etc/resolv.conf, /home, and workspace bindings..."
            )
        )

        environmentBuilder.bootstrapFilesystem(targetProjectWorkspaceDir)

        // Save metadata
        saveMetadata(manifest)

        // Clean up download archive to save space on mobile
        if (downloadFile.exists()) {
            downloadFile.delete()
        }

        // Ready!
        send(
            InstallProgress(
                state = LinuxRuntimeState.READY,
                stepTitle = "Linux Environment Ready",
                progressPercentage = 100,
                detailMessage = "${manifest.distro.displayName} ${manifest.version} (${manifest.architecture.archString}) installed successfully."
            )
        )
    }.flowOn(dispatchers.io)

    private suspend fun downloadArchive(
        urlStr: String,
        targetFile: File,
        onProgress: suspend (current: Long, total: Long, percent: Int) -> Unit
    ) {
        withContext(dispatchers.io) {
            val url = URL(urlStr)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "DevStation-Android-Runtime/1.0")
            }

            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Failed to download rootfs: HTTP $responseCode (${connection.responseMessage})")
            }

            val totalBytes = connection.contentLengthLong
            var currentBytes = 0L

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read = 0
                    var lastReportTime = 0L

                    while (coroutineContext.isActive && input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        currentBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 200 || currentBytes == totalBytes) {
                            lastReportTime = now
                            val percent = if (totalBytes > 0) ((currentBytes.toFloat() / totalBytes) * 100).toInt() else 0
                            onProgress(currentBytes, totalBytes, percent)
                        }
                    }
                }
            }

            if (!coroutineContext.isActive) {
                targetFile.delete()
                throw kotlinx.coroutines.CancellationException("Download cancelled by user.")
            }
        }
    }

    private fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || expectedSha256.isBlank()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var read = 0
            while (fis.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
        return computedHash.equals(expectedSha256.trim(), ignoreCase = true)
    }

    private fun getAvailableStorageBytes(dir: File): Long {
        return try {
            val statFs = StatFs(dir.absolutePath)
            statFs.availableBlocksLong * statFs.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
    }

    private fun saveMetadata(manifest: RootfsManifest) {
        val metaFile = File(storagePaths.metadataDir, "runtime.json")
        metaFile.writeText(
            """
            {
                "distro": "${manifest.distro.name}",
                "version": "${manifest.version}",
                "architecture": "${manifest.architecture.name}",
                "installedAt": ${System.currentTimeMillis()},
                "packageManager": "${manifest.distro.packageManagerName}",
                "status": "READY"
            }
            """.trimIndent() + "\n"
        )
    }
}
