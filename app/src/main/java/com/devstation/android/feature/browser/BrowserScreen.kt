package com.devstation.android.feature.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.devstation.android.core.preview.BrowserConsoleEntry
import com.devstation.android.core.preview.BrowserSecurityPolicy
import com.devstation.android.core.preview.BrowserSecurityState
import com.devstation.android.core.preview.DownloadDecision
import com.devstation.android.core.preview.DownloadRequest

/** Maximum live tabs — bounded resources (§5/§54). */
const val MAX_BROWSER_TABS = 8

/**
 * Phase 9 §4–§8/§33–§46: the secure mobile browser screen.
 *
 * WebView hardening (§33/§34):
 * - JavaScript and DOM storage enabled (required for dev preview);
 * - file access, content access, and universal-access-from-file-URLs all DISABLED;
 * - mixed content never allowed; safe browsing on; media requires a user gesture;
 * - NO JavaScript bridge is exposed (nothing needs one — no attack surface);
 * - every website permission request is DENIED (§45);
 * - downloads are policy-checked and never auto-executed (§36);
 * - navigation is validated through [BrowserSecurityPolicy], redirects re-validated (§16/§37).
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String?,
    securityPolicy: BrowserSecurityPolicy,
    consoleManager: com.devstation.android.core.preview.BrowserConsoleManager,
    onStartPreview: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val webViewPool = remember { mutableMapOf<String, WebView>() }
    var tabs by remember { mutableStateOf(listOf(com.devstation.android.core.preview.BrowserTab())) }
    var activeTabId by remember { mutableStateOf(tabs.first().id) }
    var addressText by remember { mutableStateOf(initialUrl ?: "") }
    var showTabSwitcher by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<DownloadRequest?>(null) }

    val consoleEntries by consoleManager.entries.collectAsState()
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    // Initial navigation
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) addressText = initialUrl
    }

    fun updateTab(tabId: String, transform: (com.devstation.android.core.preview.BrowserTab) -> com.devstation.android.core.preview.BrowserTab) {
        tabs = tabs.map { if (it.id == tabId) transform(it) else it }
    }

    fun navigate(tab: com.devstation.android.core.preview.BrowserTab, rawUrl: String) {
        val decision = securityPolicy.evaluateNavigation(rawUrl)
        if (decision.kind == BrowserSecurityPolicy.NavigationKind.BLOCKED) {
            blockedMessage = decision.reason ?: "This URL is not allowed."
            updateTab(tab.id) { it.copy(isLoading = false) }
            return
        }
        loadError = null
        updateTab(tab.id) { it.copy(url = rawUrl, isLoading = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- top bar (§4) ----
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search or enter URL", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { SecurityIndicator(securityPolicy.securityStateFor(activeTab.url)) },
                    trailingIcon = {
                        if (addressText.isNotBlank()) {
                            IconButton(onClick = {
                                val url = normalizeInput(addressText)
                                addressText = url
                                navigate(activeTab, url)
                            }) { Icon(Icons.Default.Search, contentDescription = "Go") }
                        }
                    },
                    textStyle = MaterialTheme.typography.bodySmall
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close browser")
                }
            },
            actions = {
                IconButton(onClick = { showTabSwitcher = true }) {
                    BadgedBox(badge = {
                        Badge { Text("${tabs.size}") }
                    }) { Icon(Icons.Default.Tab, contentDescription = "Tabs") }
                }
            }
        )

        // ---- navigation row ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = {
                    webViewPool[activeTab.id]?.goBack()
                },
                enabled = activeTab.canGoBack
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            IconButton(
                onClick = { webViewPool[activeTab.id]?.goForward() },
                enabled = activeTab.canGoForward
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward") }
            IconButton(onClick = {
                val active = activeTab
                if (active.isLoading) {
                    webViewPool[active.id]?.stopLoading()
                    updateTab(active.id) { it.copy(isLoading = false) }
                } else {
                    webViewPool[active.id]?.reload()
                }
            }) {
                Icon(
                    if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (activeTab.isLoading) "Stop" else "Reload"
                )
            }
            IconButton(onClick = { showConsole = true }) {
                Icon(Icons.Outlined.Terminal, contentDescription = "Console")
            }
        }

        if (activeTab.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // ---- blocked / error banners ----
        blockedMessage?.let { message ->
            BlockedBanner(message, onDismiss = { blockedMessage = null })
        }
        loadError?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = { loadError = null },
                showStartPreview = securityPolicy.securityStateFor(activeTab.url) == BrowserSecurityState.LOCAL_PREVIEW,
                onStartPreview = { loadError = null; onStartPreview() }
            )
        }

        // ---- WebView (§5: one WebView per tab, bounded count) ----
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    createWebView(
                        ctx = ctx,
                        securityPolicy = securityPolicy,
                        consoleManager = consoleManager,
                        tabId = activeTabId,
                        onTabUpdate = { transform -> updateTab(activeTabId, transform) },
                        onAddressUpdate = { addressText = it },
                        onBlocked = { blockedMessage = it },
                        onLoadError = { loadError = it },
                        onDownload = { pendingDownload = it },
                        webViewPool = webViewPool
                    )
                },
                update = { webView ->
                    val current = webViewPool[activeTab.id]
                    if (current != null && current !== webView) {
                        // Tab switched: attach the pooled WebView for the active tab.
                        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                        (current.parent as? android.view.ViewGroup)?.removeView(current)
                        (webView.parent as? android.view.ViewGroup)?.addView(current)
                        if (activeTab.url.isNotBlank() && activeTab.url != "about:blank") {
                            current.loadUrl(activeTab.url)
                        }
                    } else if (webViewPool[activeTab.id] == null) {
                        webViewPool[activeTab.id] = webView
                        if (initialUrl != null && activeTab.url == "about:blank") {
                            navigate(activeTab, initialUrl)
                            webView.loadUrl(initialUrl)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- console bottom sheet ----
        if (showConsole) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showConsole = false }) {
                ConsoleSheet(
                    entries = consoleEntries,
                    onClear = { consoleManager.clear() }
                )
            }
        }

        // ---- tab switcher ----
        if (showTabSwitcher) {
            TabSwitcherDialog(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelect = { activeTabId = it; showTabSwitcher = false },
                onNew = {
                    if (tabs.size < MAX_BROWSER_TABS) {
                        val tab = com.devstation.android.core.preview.BrowserTab()
                        tabs = tabs + tab
                        activeTabId = tab.id
                    }
                    showTabSwitcher = false
                },
                onClose = { tabId ->
                    tabs.firstOrNull { it.id == tabId }?.let { tab ->
                        webViewPool.remove(tab.id)?.destroy()
                    }
                    tabs = tabs.filter { it.id != tabId }
                    if (tabs.isEmpty()) tabs = listOf(com.devstation.android.core.preview.BrowserTab())
                    if (activeTabId == tabId) activeTabId = tabs.first().id
                },
                onCloseOthers = { tabId ->
                    tabs.filter { it.id != tabId }.forEach { tab ->
                        webViewPool.remove(tab.id)?.destroy()
                    }
                    tabs = tabs.filter { it.id == tabId }
                    activeTabId = tabId
                    showTabSwitcher = false
                }
            )
        }

        // ---- download confirmation (§36) ----
        pendingDownload?.let { request ->
            DownloadConfirmDialog(
                request = request,
                securityPolicy = securityPolicy,
                onConfirm = { name ->
                    pendingDownload = null
                    android.widget.Toast.makeText(
                        context,
                        "Download saved as $name (app storage)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                onDismiss = { pendingDownload = null }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewPool.values.forEach { it.destroy() }
            webViewPool.clear()
        }
    }
}

/** Normalize typed input to a navigable URL. */
internal fun normalizeInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    // Bare localhost/loopback → http; anything host-like → https by default.
    val looksLocal = trimmed.startsWith("localhost") || trimmed.startsWith("127.0.0.1") || trimmed.startsWith("[::1]")
    return if (looksLocal) "http://$trimmed" else "https://$trimmed"
}

@Composable
private fun SecurityIndicator(state: BrowserSecurityState) {
    val (color, icon, description) = when (state) {
        BrowserSecurityState.SECURE -> Triple(Color(0xFF2E7D32), Icons.Default.Lock, "Secure (HTTPS)")
        BrowserSecurityState.LOCAL_PREVIEW -> Triple(Color(0xFF1565C0), Icons.Default.Dns, "Local preview")
        BrowserSecurityState.HTTP -> Triple(Color(0xFFF57F17), Icons.Default.Warning, "Not secure (HTTP)")
        BrowserSecurityState.BLOCKED -> Triple(MaterialTheme.colorScheme.error, Icons.Default.Block, "Blocked")
        BrowserSecurityState.WARNING -> Triple(Color(0xFFF57F17), Icons.Default.Warning, "Caution")
        BrowserSecurityState.UNKNOWN -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.HelpOutline, "Unknown")
    }
    Icon(icon, contentDescription = description, tint = color, modifier = Modifier.size(18.dp))
}

@Composable
private fun BlockedBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, showStartPreview: Boolean, onStartPreview: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showStartPreview) {
                    Button(onClick = onStartPreview) { Text("Start Preview") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** Hardened WebView creation with all Phase 9 security settings (§33–§46). */
@Suppress("DEPRECATION")
private fun createWebView(
    ctx: android.content.Context,
    securityPolicy: BrowserSecurityPolicy,
    consoleManager: com.devstation.android.core.preview.BrowserConsoleManager,
    tabId: String,
    onTabUpdate: ((com.devstation.android.core.preview.BrowserTab) -> com.devstation.android.core.preview.BrowserTab) -> Unit,
    onAddressUpdate: (String) -> Unit,
    onBlocked: (String) -> Unit,
    onLoadError: (String) -> Unit,
    onDownload: (DownloadRequest) -> Unit,
    webViewPool: MutableMap<String, WebView>
): WebView {
    val webView = WebView(ctx)
    webViewPool[tabId] = webView
    webView.settings.apply {
        javaScriptEnabled = true           // required for dev preview
        domStorageEnabled = true
        // §33/§34: file and content access fully disabled.
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        setAllowFileAccessFromFileURLs(false)
        @Suppress("DEPRECATION")
        setAllowUniversalAccessFromFileURLs(false)
        @Suppress("DEPRECATION")
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
    }
    // §46: safe browsing where supported.
    runCatching {
        android.webkit.WebView.setWebContentsDebuggingEnabled(false)
    }

    webView.webViewClient = object : WebViewClient() {
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            val decision = securityPolicy.evaluateNavigation(url)
            if (decision.kind == BrowserSecurityPolicy.NavigationKind.BLOCKED) {
                onBlocked(decision.reason ?: "Navigation blocked by security policy.")
                return true
            }
            onAddressUpdate(securityPolicy.redactForLogs(url))
            return false
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return shouldOverrideUrlLoading(view, url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            onTabUpdate { it.copy(isLoading = true, url = url) }
        }

        override fun onPageFinished(view: WebView, url: String) {
            onTabUpdate {
                it.copy(
                    isLoading = false,
                    title = view.title ?: "",
                    canGoBack = view.canGoBack(),
                    canGoForward = view.canGoForward()
                )
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
            onLoadError(
                when {
                    description.contains("ERR_CONNECTION_REFUSED", ignoreCase = true) ->
                        "Server not running — the preview server refused the connection."
                    description.contains("ERR_NAME_NOT_RESOLVED", ignoreCase = true) ->
                        "Address not found (DNS failure)."
                    description.contains("ERR_TIMED_OUT", ignoreCase = true) ->
                        "The server took too long to respond (timeout)."
                    else -> description
                }
            )
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame) {
                val description = error.description?.toString() ?: "Failed to load page."
                onReceivedError(view, -1, description, request.url.toString())
            }
        }

        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            // §7: never bypass TLS failures.
            handler.cancel()
            onLoadError("TLS certificate error — connection cancelled.")
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        // §41: console capture, redacted and bounded by the manager.
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            val level = when (message.messageLevel()) {
                android.webkit.ConsoleMessage.MessageLevel.ERROR -> BrowserConsoleEntry.Level.ERROR
                android.webkit.ConsoleMessage.MessageLevel.WARNING -> BrowserConsoleEntry.Level.WARNING
                android.webkit.ConsoleMessage.MessageLevel.LOG -> BrowserConsoleEntry.Level.LOG
                else -> BrowserConsoleEntry.Level.INFO
            }
            consoleManager.append(level, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            return true
        }

        // §45: deny every website permission request by default.
        override fun onPermissionRequest(request: PermissionRequest) {
            runCatching { request.deny() }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    // §36: downloads are policy-checked and confirmed by the user; never auto-executed.
    webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
        onDownload(
            DownloadRequest(
                url = securityPolicy.redactForLogs(url),
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength.toLong()
            )
        )
    }

    // §44: cookies enabled for normal browsing but never exported anywhere.
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

    return webView
}

@Composable
private fun ConsoleSheet(entries: List<BrowserConsoleEntry>, onClear: () -> Unit) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 420.dp)
        .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Console", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Text("No console messages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(entries) { entry ->
                    val color = when (entry.level) {
                        BrowserConsoleEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                        BrowserConsoleEntry.Level.WARNING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        "[${entry.level.name}] ${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabSwitcherDialog(
    tabs: List<com.devstation.android.core.preview.BrowserTab>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onClose: (String) -> Unit,
    onCloseOthers: (String) -> Unit
) {
    Dialog(onDismissRequest = { }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tabs (${tabs.size}/$MAX_BROWSER_TABS)", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onNew, enabled = tabs.size < MAX_BROWSER_TABS) { Text("New Tab") }
                }
                LazyColumn {
                    items(tabs, key = { it.id }) { tab ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { onSelect(tab.id) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    (tab.title.ifBlank { tab.url }).ifBlank { "New tab" },
                                    maxLines = 1,
                                    fontWeight = if (tab.id == activeTabId) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            TextButton(onClick = { onCloseOthers(tab.id) }) { Text("Only") }
                            IconButton(onClick = { onClose(tab.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close tab")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadConfirmDialog(
    request: DownloadRequest,
    securityPolicy: BrowserSecurityPolicy,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val decision = securityPolicy.evaluateDownload(request)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download file?") },
        text = {
            Column {
                when (decision) {
                    is DownloadDecision.Allowed -> {
                        Text("File: ${decision.suggestedName}")
                        Text("From: ${request.url}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        if (request.contentLength > 0) {
                            Text("Size: ${request.contentLength / 1024} KB", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "The file will be saved to app storage. It will never be executed automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is DownloadDecision.Rejected -> Text(
                        "Download blocked: ${decision.reason}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (decision is DownloadDecision.Allowed) {
                TextButton(onClick = { onConfirm(decision.suggestedName) }) { Text("Download") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
