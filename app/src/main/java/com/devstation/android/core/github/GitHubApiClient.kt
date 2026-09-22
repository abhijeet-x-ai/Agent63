package com.devstation.android.core.github

import com.devstation.android.core.agent.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

interface GitHubApiClient {
    suspend fun getUser(token: String): Result<GitHubUser>
    suspend fun listRepositories(token: String, page: Int = 1, perPage: Int = 30): Result<List<GitHubRepository>>
    suspend fun createRepository(token: String, request: CreateRepositoryRequest): Result<GitHubRepository>
    suspend fun listPullRequests(token: String, owner: String, repo: String, state: String = "open", page: Int = 1): Result<List<GitHubPullRequest>>
    suspend fun createPullRequest(token: String, owner: String, repo: String, request: CreatePullRequestRequest): Result<GitHubPullRequest>
    suspend fun listIssues(token: String, owner: String, repo: String, state: String = "open", page: Int = 1): Result<List<GitHubIssue>>
    suspend fun createIssue(token: String, owner: String, repo: String, request: CreateIssueRequest): Result<GitHubIssue>
}

class DefaultGitHubApiClient(
    private val baseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : GitHubApiClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun checkBaseUrl() {
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://127.0.0.1") && !baseUrl.startsWith("http://localhost")) {
            throw SecurityException("Insecure non-HTTPS GitHub API URL rejected: $baseUrl")
        }
    }

    private suspend fun executeRequest(token: String, requestBuilder: Request.Builder): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            checkBaseUrl()
            val sanitizedToken = token.trim()
            require(sanitizedToken.isNotBlank()) { "GitHub personal access token cannot be empty" }

            val req = requestBuilder
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DevStation-Mobile/1.0")
                .header("Authorization", "Bearer $sanitizedToken")
                .build()

            val response = client.newCall(req).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val redactedBody = SecretRedactor.redact(body)
                val msg = when (code) {
                    401 -> "GitHub authentication failed: Bad credentials or expired token."
                    403 -> "GitHub API rate limit exceeded or insufficient permissions (403): $redactedBody"
                    404 -> "GitHub resource not found (404): $redactedBody"
                    422 -> "GitHub validation failed (422): $redactedBody"
                    else -> "GitHub API error HTTP $code: $redactedBody"
                }
                throw IOException(msg)
            }
            body
        }.recoverCatching { e ->
            val redacted = SecretRedactor.redact(e.message ?: "Unknown GitHub network error")
            throw IOException(redacted, e.cause)
        }
    }

    override suspend fun getUser(token: String): Result<GitHubUser> {
        val url = "$baseUrl/user"
        val req = Request.Builder().url(url).get()
        return executeRequest(token, req).mapCatching { body ->
            parseUser(json.parseToJsonElement(body).jsonObject)
        }
    }

    override suspend fun listRepositories(token: String, page: Int, perPage: Int): Result<List<GitHubRepository>> {
        val url = "$baseUrl/user/repos?page=$page&per_page=$perPage&sort=updated"
        val req = Request.Builder().url(url).get()
        return executeRequest(token, req).mapCatching { body ->
            val arr = json.parseToJsonElement(body) as JsonArray
            arr.map { parseRepo(it.jsonObject) }
        }
    }

    override suspend fun createRepository(token: String, request: CreateRepositoryRequest): Result<GitHubRepository> {
        val url = "$baseUrl/user/repos"
        val payload = buildString {
            append("{")
            append("\"name\": \"").append(escapeJson(request.name)).append("\"")
            if (request.description != null) {
                append(", \"description\": \"").append(escapeJson(request.description)).append("\"")
            }
            append(", \"private\": ").append(request.isPrivate)
            append(", \"auto_init\": ").append(request.autoInit)
            append("}")
        }
        val req = Request.Builder().url(url).post(payload.toRequestBody(jsonMediaType))
        return executeRequest(token, req).mapCatching { body ->
            parseRepo(json.parseToJsonElement(body).jsonObject)
        }
    }

    override suspend fun listPullRequests(token: String, owner: String, repo: String, state: String, page: Int): Result<List<GitHubPullRequest>> {
        val url = "$baseUrl/repos/$owner/$repo/pulls?state=$state&page=$page"
        val req = Request.Builder().url(url).get()
        return executeRequest(token, req).mapCatching { body ->
            val arr = json.parseToJsonElement(body) as JsonArray
            arr.map { parsePullRequest(it.jsonObject) }
        }
    }

    override suspend fun createPullRequest(token: String, owner: String, repo: String, request: CreatePullRequestRequest): Result<GitHubPullRequest> {
        val url = "$baseUrl/repos/$owner/$repo/pulls"
        val payload = buildString {
            append("{")
            append("\"title\": \"").append(escapeJson(request.title)).append("\",")
            append("\"head\": \"").append(escapeJson(request.head)).append("\",")
            append("\"base\": \"").append(escapeJson(request.base)).append("\"")
            if (request.body != null) {
                append(", \"body\": \"").append(escapeJson(request.body)).append("\"")
            }
            append(", \"draft\": ").append(request.draft)
            append("}")
        }
        val req = Request.Builder().url(url).post(payload.toRequestBody(jsonMediaType))
        return executeRequest(token, req).mapCatching { body ->
            parsePullRequest(json.parseToJsonElement(body).jsonObject)
        }
    }

    override suspend fun listIssues(token: String, owner: String, repo: String, state: String, page: Int): Result<List<GitHubIssue>> {
        val url = "$baseUrl/repos/$owner/$repo/issues?state=$state&page=$page"
        val req = Request.Builder().url(url).get()
        return executeRequest(token, req).mapCatching { body ->
            val arr = json.parseToJsonElement(body) as JsonArray
            // GitHub issues endpoint includes pull requests; filter them out if "pull_request" object exists
            arr.filter { "pull_request" !in it.jsonObject }.map { parseIssue(it.jsonObject) }
        }
    }

    override suspend fun createIssue(token: String, owner: String, repo: String, request: CreateIssueRequest): Result<GitHubIssue> {
        val url = "$baseUrl/repos/$owner/$repo/issues"
        val payload = buildString {
            append("{")
            append("\"title\": \"").append(escapeJson(request.title)).append("\"")
            if (request.body != null) {
                append(", \"body\": \"").append(escapeJson(request.body)).append("\"")
            }
            if (request.labels.isNotEmpty()) {
                append(", \"labels\": [")
                request.labels.forEachIndexed { i, l ->
                    if (i > 0) append(",")
                    append("\"").append(escapeJson(l)).append("\"")
                }
                append("]")
            }
            append("}")
        }
        val req = Request.Builder().url(url).post(payload.toRequestBody(jsonMediaType))
        return executeRequest(token, req).mapCatching { body ->
            parseIssue(json.parseToJsonElement(body).jsonObject)
        }
    }

    private fun parseUser(obj: JsonObject): GitHubUser {
        return GitHubUser(
            id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            login = obj["login"]?.jsonPrimitive?.content ?: "",
            name = obj["name"]?.jsonPrimitive?.content,
            avatarUrl = obj["avatar_url"]?.jsonPrimitive?.content,
            htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: "",
            publicRepos = obj["public_repos"]?.jsonPrimitive?.intOrNull ?: 0,
            totalPrivateRepos = obj["total_private_repos"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    private fun parseRepo(obj: JsonObject): GitHubRepository {
        return GitHubRepository(
            id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            fullName = obj["full_name"]?.jsonPrimitive?.content ?: "",
            description = obj["description"]?.jsonPrimitive?.content,
            isPrivate = obj["private"]?.jsonPrimitive?.booleanOrNull ?: false,
            htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: "",
            cloneUrl = obj["clone_url"]?.jsonPrimitive?.content ?: "",
            defaultBranch = obj["default_branch"]?.jsonPrimitive?.content ?: "main",
            stars = obj["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0,
            forks = obj["forks_count"]?.jsonPrimitive?.intOrNull ?: 0,
            openIssues = obj["open_issues_count"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedAt = obj["updated_at"]?.jsonPrimitive?.content ?: ""
        )
    }

    private fun parsePullRequest(obj: JsonObject): GitHubPullRequest {
        val userObj = obj["user"] as? JsonObject
        val headObj = obj["head"] as? JsonObject
        val baseObj = obj["base"] as? JsonObject
        return GitHubPullRequest(
            id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0,
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            body = obj["body"]?.jsonPrimitive?.content,
            state = obj["state"]?.jsonPrimitive?.content ?: "open",
            htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: "",
            user = userObj?.let { parseUser(it) },
            headRef = headObj?.get("ref")?.jsonPrimitive?.content ?: "",
            baseRef = baseObj?.get("ref")?.jsonPrimitive?.content ?: "",
            isDraft = obj["draft"]?.jsonPrimitive?.booleanOrNull ?: false,
            createdAt = obj["created_at"]?.jsonPrimitive?.content ?: "",
            mergedAt = obj["merged_at"]?.jsonPrimitive?.content
        )
    }

    private fun parseIssue(obj: JsonObject): GitHubIssue {
        val userObj = obj["user"] as? JsonObject
        return GitHubIssue(
            id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0,
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            body = obj["body"]?.jsonPrimitive?.content,
            state = obj["state"]?.jsonPrimitive?.content ?: "open",
            htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: "",
            user = userObj?.let { parseUser(it) },
            commentsCount = obj["comments"]?.jsonPrimitive?.intOrNull ?: 0,
            createdAt = obj["created_at"]?.jsonPrimitive?.content ?: ""
        )
    }

    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000c", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
