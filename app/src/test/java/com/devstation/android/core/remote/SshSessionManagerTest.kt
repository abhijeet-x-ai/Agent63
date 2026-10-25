package com.devstation.android.core.remote

import com.devstation.android.core.database.SshHostDao
import com.devstation.android.core.database.SshHostEntity
import com.devstation.android.core.security.SecureCredentialStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSessionManagerTest {

    private class FakeSecureStore : SecureCredentialStore {
        val values = mutableMapOf<String, String>()
        override fun storeSecret(alias: String, secret: String): Result<Unit> {
            values[alias] = secret
            return Result.success(Unit)
        }
        override fun getSecret(alias: String): Result<String?> = Result.success(values[alias])
        override fun removeSecret(alias: String): Result<Unit> {
            values.remove(alias)
            return Result.success(Unit)
        }
        override fun hasSecret(alias: String): Boolean = values.containsKey(alias)
        override fun listAliases(): List<String> = values.keys.toList()
    }

    private class FakeSshHostDao : SshHostDao {
        val rows = mutableMapOf<String, SshHostEntity>()
        private val flowState = MutableStateFlow<List<SshHostEntity>>(emptyList())
        private fun publish() {
            flowState.value = rows.values.sortedBy { it.name }
        }
        override fun getAllHostsFlow(): Flow<List<SshHostEntity>> = flowState
        override suspend fun getAllHosts(): List<SshHostEntity> = rows.values.toList()
        override suspend fun getHostById(id: String): SshHostEntity? = rows[id]
        override suspend fun insertHost(host: SshHostEntity) {
            rows[host.id] = host
            publish()
        }
        override suspend fun deleteHostById(id: String) {
            rows.remove(id)
            publish()
        }
        override suspend fun updateHostKey(id: String, fingerprint: String?, keyType: String?, updatedAt: Long) {
            rows[id]?.let {
                rows[id] = it.copy(hostKeyFingerprint = fingerprint, hostKeyType = keyType, updatedAt = updatedAt)
            }
        }
        override suspend fun touchLastConnected(id: String, connectedAt: Long) {
            rows[id]?.let { rows[id] = it.copy(lastConnectedAt = connectedAt, updatedAt = connectedAt) }
        }
    }

    private class FakeSshClient : SshClient {
        var connectFailure: Throwable? = null
        var lastRequest: SshConnectRequest? = null
        private var connected = false
        override val isConnected: Boolean get() = connected
        override fun connect(request: SshConnectRequest) {
            lastRequest = request
            connectFailure?.let { throw it }
            connected = true
        }
        override fun disconnect() {
            connected = false
        }
        override fun exec(command: String, timeoutMs: Long, maxOutputChars: Int): RemoteExecResult =
            RemoteExecResult(exitCode = 0, stdout = "ran:$command", stderr = "", truncated = false)
        override fun openShell(cols: Int, rows: Int): SshShellChannel =
            throw UnsupportedOperationException("not used in these tests")
        override fun openSftp(): SshSftpChannel =
            throw UnsupportedOperationException("not used in these tests")
    }

    private val dao = FakeSshHostDao()
    private val credentials = SshCredentialManager(FakeSecureStore())
    private val repository = SshHostRepository(dao, credentials)
    private val fakeClient = FakeSshClient()
    private val manager = SshSessionManager(repository, credentials, clientFactory = { fakeClient })

    private val profile = SshHostProfile(
        id = "h1",
        name = "Test server",
        host = "example.com",
        port = 22,
        username = "dev",
        authType = SshAuthType.PASSWORD,
        hostKeyFingerprint = "SHA256:trusted"
    )

    private fun seed(withSecret: Boolean = true) = runBlocking {
        repository.save(profile)
        if (withSecret) credentials.savePassword("h1", "pw")
    }

    @Test
    fun `connect succeeds, passes the stored fingerprint, and marks the host connected`() = runBlocking {
        seed()
        val result = manager.connectProfile("h1")
        assertTrue(result.isSuccess)
        assertEquals(RemoteSessionState.CONNECTED, manager.sessionState.value)
        assertTrue(manager.isConnected.first())
        assertEquals("SHA256:trusted", fakeClient.lastRequest?.storedHostKeyFingerprint)
        assertEquals("example.com", fakeClient.lastRequest?.host)
        assertNotNull(dao.rows["h1"]?.lastConnectedAt)
    }

    @Test
    fun `unknown host key surfaces as a typed failure`() = runBlocking {
        seed()
        fakeClient.connectFailure = HostKeyUnknownException("SHA256:new", "EdDSA")
        val result = manager.connectProfile("h1")
        assertTrue(result.isFailure)
        assertEquals(RemoteSessionState.FAILED, manager.sessionState.value)
        assertTrue(manager.lastFailure.value is HostKeyUnknownException)
    }

    @Test
    fun `missing secret fails before any connection attempt`() = runBlocking {
        seed(withSecret = false)
        val result = manager.connectProfile("h1")
        assertTrue(result.isFailure)
        assertEquals(RemoteSessionState.FAILED, manager.sessionState.value)
        assertEquals(null, fakeClient.lastRequest)
    }

    @Test
    fun `unknown profile id fails cleanly`() = runBlocking {
        val result = manager.connectProfile("missing")
        assertTrue(result.isFailure)
    }

    @Test
    fun `exec requires a connection and then delegates to the client`() = runBlocking {
        seed()
        assertTrue(manager.exec("ls").isFailure)
        manager.connectProfile("h1")
        val result = manager.exec("ls")
        assertTrue(result.isSuccess)
        assertEquals("ran:ls", result.getOrNull()?.stdout)
    }

    @Test
    fun `disconnect returns to disconnected state`() = runBlocking {
        seed()
        manager.connectProfile("h1")
        manager.disconnect()
        assertEquals(RemoteSessionState.DISCONNECTED, manager.sessionState.value)
        assertTrue(!manager.isConnected.first())
    }

    @Test
    fun `sync without a connection fails`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "devstation-sync-test")
        dir.mkdirs()
        val result = manager.syncLocalToRemote(dir.absolutePath, "/tmp/remote")
        assertTrue(result.isFailure)
    }
}
