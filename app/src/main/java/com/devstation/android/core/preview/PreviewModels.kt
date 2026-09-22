package com.devstation.android.core.preview

import java.util.UUID

/**
 * Phase 9: preview + browser data models. Pure data — nothing here executes.
 */

/** Lifecycle of a managed preview server. */
enum class PreviewServerState {
    STOPPED, STARTING, RUNNING, STOPPING, FAILED, CRASHED
}

/** Who created a preview server. */
enum class PreviewOwner { USER, AGENT }

/**
 * A managed local development server bound to one project.
 */
data class PreviewServer(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val projectName: String = "",
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String,
    val port: Int,
    val host: String = "127.0.0.1",
    val state: PreviewServerState = PreviewServerState.STOPPED,
    val owner: PreviewOwner = PreviewOwner.USER,
    val startedAt: Long? = null,
    val restartCount: Int = 0,
    val lastError: String? = null,
    val readinessUrl: String? = null
) {
    val url: String get() = "http://localhost:$port"
}

/** One bounded console/log entry from a preview server. */
data class PreviewLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val stream: Stream,
    val text: String
) {
    enum class Stream { STDOUT, STDERR, SYSTEM }
}

/** One browser tab. */
data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "about:blank",
    val title: String = "",
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

/** Browser security state shown in the UI (§46). */
enum class BrowserSecurityState { SECURE, LOCAL_PREVIEW, HTTP, BLOCKED, WARNING, UNKNOWN }

/** A browser console message (§41). */
data class BrowserConsoleEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Level,
    val message: String
) {
    enum class Level { LOG, INFO, WARNING, ERROR }
}

/** A download request intercepted from the browser (§36). */
data class DownloadRequest(
    val url: String,
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val contentLength: Long = -1
)

/** Result of evaluating a download against policy. */
sealed class DownloadDecision {
    /** Download permitted to [suggestedName] in the app's private downloads dir. */
    data class Allowed(val suggestedName: String) : DownloadDecision()
    data class Rejected(val reason: String) : DownloadDecision()
}
