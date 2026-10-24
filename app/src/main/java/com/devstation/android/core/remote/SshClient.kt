package com.devstation.android.core.remote

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Phase 11: the SSH seam.
 *
 * Everything SSH-library-specific lives behind this interface (today: the
 * sshj implementation in [SshjClient]) so the library stays replaceable and
 * the session manager + sync engine can be unit-tested with a fake.
 *
 * All functions are blocking; callers run them on an IO dispatcher.
 */

/** Authentication material for one connection, loaded from the credential store. */
sealed interface SshAuthMaterial {
    data class Password(val password: String) : SshAuthMaterial
    data class PrivateKey(val pem: String, val passphrase: String?) : SshAuthMaterial
}

/** Everything [SshClient.connect] needs for one connection attempt. */
data class SshConnectRequest(
    val host: String,
    val port: Int,
    val username: String,
    val auth: SshAuthMaterial,
    /** Trusted fingerprint for this host, or null when none is stored yet (TOFU). */
    val storedHostKeyFingerprint: String?,
    val connectTimeoutMs: Int = 15_000
)

/** The server presented a host key but no fingerprint is stored for the host yet. */
class HostKeyUnknownException(
    val fingerprint: String,
    val keyType: String
) : Exception("Server host key is not trusted yet ($keyType $fingerprint)")

/** The server's host key does not match the stored trusted fingerprint. */
class HostKeyChangedException(
    val storedFingerprint: String,
    val presentedFingerprint: String
) : Exception("Server host key has CHANGED: stored $storedFingerprint, presented $presentedFingerprint")

/** Interactive shell channel over an established connection. */
interface SshShellChannel {
    /** Data arriving from the server (stdout; stderr is merged under a PTY). */
    val inputStream: InputStream

    /** Data to send to the server. */
    val outputStream: OutputStream

    val errorStream: InputStream

    fun resize(cols: Int, rows: Int)

    fun close()
}

/** Minimal SFTP surface used by the sync engine. */
interface SshSftpChannel {
    /** Lists one remote directory (non-recursive). */
    fun list(remoteDir: String): List<SyncFileInfo>

    /** Stat one remote path, or null when it does not exist. */
    fun stat(remotePath: String): SyncFileInfo?

    /** Creates a remote directory and any missing parents. */
    fun mkdirs(remotePath: String)

    /** Uploads one local file to an exact remote path. */
    fun upload(localFile: File, remotePath: String)

    fun close()
}

interface SshClient {
    val isConnected: Boolean

    /**
     * Connects and authenticates.
     *
     * @throws HostKeyUnknownException when the host key is not trusted yet
     * @throws HostKeyChangedException when the host key does not match the stored one
     */
    fun connect(request: SshConnectRequest)

    fun disconnect()

    /** Runs one command to completion with bounded output and a hard timeout. */
    fun exec(command: String, timeoutMs: Long, maxOutputChars: Int): RemoteExecResult

    /** Opens an interactive shell with a PTY of the given size. */
    fun openShell(cols: Int, rows: Int): SshShellChannel

    fun openSftp(): SshSftpChannel
}
