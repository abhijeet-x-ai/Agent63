package com.devstation.android.core.remote

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Phase 11: sshj-backed [SshClient].
 *
 * This is the ONLY class in the app that imports sshj types (plus the SFTP
 * and key-provider types it needs), so the SSH library stays replaceable.
 *
 * Host-key policy is TOFU and fail-closed: the verifier below never accepts
 * an unknown or changed key. Instead it records what the server presented
 * and returns false; [connect] then rethrows the precise
 * [HostKeyUnknownException] / [HostKeyChangedException] so the caller can ask
 * the user to trust the key and retry.
 */
class SshjClient : SshClient {

    private var ssh: SSHClient? = null

    @Volatile private var unknownKey: HostKeyUnknownException? = null
    @Volatile private var changedKey: HostKeyChangedException? = null

    override val isConnected: Boolean
        get() = ssh?.let { it.isConnected && it.isAuthenticated } == true

    override fun connect(request: SshConnectRequest) {
        disconnect()
        unknownKey = null
        changedKey = null
        ensureBouncyCastle()

        val client = SSHClient()
        client.addHostKeyVerifier(TofuVerifier(request.storedHostKeyFingerprint))
        try {
            client.connect(request.host, request.port)
            when (val auth = request.auth) {
                is SshAuthMaterial.Password ->
                    client.authPassword(request.username, auth.password)
                is SshAuthMaterial.PrivateKey ->
                    client.authPublickey(request.username, keyProvider(auth))
            }
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            unknownKey?.let { throw it }
            changedKey?.let { throw it }
            throw t
        }
        ssh = client
    }

    override fun disconnect() {
        val client = ssh
        ssh = null
        if (client != null) runCatching { client.disconnect() }
    }

    override fun exec(command: String, timeoutMs: Long, maxOutputChars: Int): RemoteExecResult {
        val client = ssh ?: error("Not connected")
        val session = client.startSession()
        try {
            val cmd = session.exec(command)
            val stdout = BoundedTextBuffer(maxOutputChars)
            val stderr = BoundedTextBuffer(maxOutputChars)
            val outThread = thread(isDaemon = true) { pump(cmd.inputStream, stdout) }
            val errThread = thread(isDaemon = true) { pump(cmd.errorStream, stderr) }
            cmd.join(timeoutMs, TimeUnit.MILLISECONDS)
            val timedOut = cmd.isOpen
            if (timedOut) runCatching { cmd.close() }
            outThread.join(2_000)
            errThread.join(2_000)
            return RemoteExecResult(
                exitCode = if (timedOut) null else cmd.exitStatus,
                stdout = stdout.text(),
                stderr = stderr.text(),
                truncated = timedOut || stdout.truncated || stderr.truncated
            )
        } finally {
            runCatching { session.close() }
        }
    }

    override fun openShell(cols: Int, rows: Int): SshShellChannel {
        val client = ssh ?: error("Not connected")
        val session = client.startSession()
        session.allocateDefaultPTY()
        val shell = session.startShell()
        if (cols > 0 && rows > 0) {
            runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
        }
        return object : SshShellChannel {
            override val inputStream: InputStream get() = shell.inputStream
            override val outputStream: OutputStream get() = shell.outputStream
            override val errorStream: InputStream get() = shell.errorStream

            override fun resize(cols: Int, rows: Int) {
                if (cols > 0 && rows > 0) {
                    runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
                }
            }

            override fun close() {
                runCatching { shell.close() }
                runCatching { session.close() }
            }
        }
    }

    override fun openSftp(): SshSftpChannel {
        val client = ssh ?: error("Not connected")
        val sftp = client.newSFTPClient()
        return object : SshSftpChannel {
            override fun list(remoteDir: String): List<SyncFileInfo> =
                sftp.ls(remoteDir).map { info ->
                    SyncFileInfo(
                        relativePath = info.name,
                        sizeBytes = info.attributes.size,
                        modifiedAtMs = info.attributes.mtime * 1_000L,
                        isDirectory = info.isDirectory
                    )
                }

            override fun stat(remotePath: String): SyncFileInfo? {
                val attrs = sftp.statExistence(remotePath) ?: return null
                return SyncFileInfo(
                    relativePath = remotePath.substringAfterLast('/'),
                    sizeBytes = attrs.size,
                    modifiedAtMs = attrs.mtime * 1_000L,
                    isDirectory = attrs.type == FileMode.Type.DIRECTORY
                )
            }

            override fun mkdirs(remotePath: String) {
                sftp.mkdirs(remotePath)
            }

            override fun upload(localFile: File, remotePath: String) {
                sftp.put(localFile.absolutePath, remotePath)
            }

            override fun close() {
                runCatching { sftp.close() }
            }
        }
    }

    /** TOFU verifier: trusts only an exact fingerprint match; records everything else. */
    private inner class TofuVerifier(
        private val storedFingerprint: String?
    ) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val blob = Buffer.PlainBuffer().apply { putPublicKey(key) }.compactData
            val fingerprint = HostKeyFingerprints.sha256Fingerprint(blob)
            return when (hostKeyTrustState(storedFingerprint, fingerprint)) {
                HostKeyTrustState.TRUSTED -> true
                HostKeyTrustState.UNKNOWN -> {
                    unknownKey = HostKeyUnknownException(fingerprint, key.algorithm ?: "unknown")
                    false
                }
                HostKeyTrustState.CHANGED -> {
                    changedKey = HostKeyChangedException(storedFingerprint.orEmpty(), fingerprint)
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private fun keyProvider(material: SshAuthMaterial.PrivateKey): FileKeyProvider {
        val finder = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray =
                material.passphrase?.toCharArray() ?: CharArray(0)

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
        val provider: FileKeyProvider =
            if (material.pem.contains("OPENSSH PRIVATE KEY")) OpenSSHKeyFile() else PKCS8KeyFile()
        provider.init(material.pem, null, finder)
        return provider
    }

    private fun pump(input: InputStream, buffer: BoundedTextBuffer) {
        val chunk = ByteArray(8_192)
        try {
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read > 0) buffer.append(String(chunk, 0, read, Charsets.UTF_8))
            }
        } catch (_: Throwable) {
            // Stream closed by timeout/close path — whatever was pumped stands.
        }
    }

    /** Thread-safe text accumulator with a hard character cap. */
    private class BoundedTextBuffer(private val maxChars: Int) {
        private val builder = StringBuilder()
        var truncated: Boolean = false
            private set

        @Synchronized
        fun append(text: String) {
            val room = maxChars - builder.length
            if (room <= 0) {
                truncated = true
                return
            }
            if (text.length > room) {
                builder.append(text.substring(0, room))
                truncated = true
            } else {
                builder.append(text)
            }
        }

        @Synchronized
        fun text(): String = builder.toString()
    }

    private companion object {
        /**
         * sshj needs a full BouncyCastle provider; Android ships a stripped
         * provider under the same "BC" name, so replace it with the real one
         * bundled via bcprov-jdk18on. No-op once the real provider is in.
         */
        fun ensureBouncyCastle() {
            val current = Security.getProvider("BC")
            if (current != null && current.javaClass.name == BouncyCastleProvider::class.java.name) return
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
