package com.devstation.android.core.github

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubSecurityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testInsecureNonLocalHttpUrlRejected() = runTest {
        val client = DefaultGitHubApiClient(baseUrl = "http://api.github.com.evil.com")
        val result = client.getUser("ghp_fake12345678901234567890")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Insecure") == true)
    }

    @Test
    fun testSecretTokenRedactedFromErrors() = runTest {
        val token = "ghp_superSecretToken1234567890"
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("{\"message\":\"Bad credentials for token $token\"}")
        )

        val client = DefaultGitHubApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        val result = client.getUser(token)
        assertTrue(result.isFailure)

        val errorMsg = result.exceptionOrNull()?.message.orEmpty()
        assertFalse("Error message must not leak token", errorMsg.contains("ghp_superSecretToken1234567890"))
    }

    @Test
    fun testGetUserSuccess() = runTest {
        val jsonResponse = """
            {
                "id": 123456,
                "login": "octocat",
                "name": "The Octocat",
                "avatar_url": "https://avatars.githubusercontent.com/u/123456",
                "html_url": "https://github.com/octocat",
                "public_repos": 10,
                "total_private_repos": 5
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val client = DefaultGitHubApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        val result = client.getUser("ghp_validToken1234567890")

        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals(123456L, user.id)
        assertEquals("octocat", user.login)
        assertEquals("The Octocat", user.name)
        assertEquals(10, user.publicRepos)
        assertEquals(5, user.totalPrivateRepos)

        // Verify request headers
        val recorded = server.takeRequest()
        assertEquals("Bearer ghp_validToken1234567890", recorded.getHeader("Authorization"))
        assertEquals("application/vnd.github.v3+json", recorded.getHeader("Accept"))
    }

    @Test
    fun testCreatePullRequestJsonPayload() = runTest {
        val jsonResponse = """
            {
                "id": 987,
                "number": 42,
                "title": "Add Phase 10 Git",
                "body": "Implements Git & GitHub features",
                "state": "open",
                "html_url": "https://github.com/octocat/repo/pull/42",
                "head": { "ref": "feature/git" },
                "base": { "ref": "main" },
                "draft": false
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(201).setBody(jsonResponse))

        val client = DefaultGitHubApiClient(baseUrl = server.url("").toString().trimEnd('/'))
        val request = CreatePullRequestRequest(
            title = "Add Phase 10 Git",
            head = "feature/git",
            base = "main",
            body = "Implements Git & GitHub features"
        )
        val result = client.createPullRequest("ghp_token1234567890123", "octocat", "repo", request)

        assertTrue(result.isSuccess)
        val pr = result.getOrThrow()
        assertEquals(42, pr.number)
        assertEquals("feature/git", pr.headRef)
        assertEquals("main", pr.baseRef)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"title\": \"Add Phase 10 Git\""))
        assertTrue(body.contains("\"head\": \"feature/git\""))
        assertTrue(body.contains("\"base\": \"main\""))
    }
}
