package com.devstation.android.future.browser

import kotlinx.coroutines.flow.Flow

/**
 * Extension contract for Phase 9: Browser & Live Preview Automation.
 */
interface BrowserController {
    val currentUrl: Flow<String?>
    val isEvaluating: Boolean

    suspend fun navigate(url: String)
    suspend fun reload()
    suspend fun evaluateJavascript(script: String): Result<String>
    suspend fun captureScreenshot(): Result<ByteArray>
}
