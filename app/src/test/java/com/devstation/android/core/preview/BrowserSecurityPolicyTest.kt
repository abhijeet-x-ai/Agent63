package com.devstation.android.core.preview

import com.devstation.android.core.security.policy.NetworkIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 §58/§59/§60/§63/§64: browser security, SSRF, localhost spoofing,
 * redirect validation, download policy. All violations must fail closed.
 */
class BrowserSecurityPolicyTest {

    private val policy = BrowserSecurityPolicy(allowPlainHttp = true)
    private val httpsOnly = BrowserSecurityPolicy(allowPlainHttp = false)

    // ---- §58.1–§58.5 normal navigation ----

    @Test
    fun `https navigation is SECURE`() {
        val d = policy.evaluateNavigation("https://example.com/page")
        assertEquals(BrowserSecurityPolicy.NavigationKind.EXTERNAL_HTTPS, d.kind)
        assertEquals(BrowserSecurityState.SECURE, d.securityState)
    }

    @Test
    fun `plain http external navigation allowed but visibly HTTP`() {
        val d = policy.evaluateNavigation("http://example.com/")
        assertEquals(BrowserSecurityPolicy.NavigationKind.EXTERNAL_HTTP, d.kind)
        assertEquals(BrowserSecurityState.HTTP, d.securityState)
    }

    @Test
    fun `httpsOnly policy blocks plain http`() {
        val d = httpsOnly.evaluateNavigation("http://example.com/")
        assertEquals(BrowserSecurityPolicy.NavigationKind.BLOCKED, d.kind)
    }

    @Test
    fun `localhost variants classify as LOCAL_PREVIEW`() {
        for (url in listOf(
            "http://localhost:3000",
            "http://127.0.0.1:5173",
            "http://[::1]:8080"
        )) {
            val d = policy.evaluateNavigation(url)
            assertEquals(url, BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, d.kind)
            assertEquals(BrowserSecurityState.LOCAL_PREVIEW, d.securityState)
        }
    }

    // ---- §58.6–§58.11 dangerous schemes (§6) ----

    @Test
    fun `dangerous schemes are blocked`() {
        for (url in listOf(
            "file:///etc/passwd",
            "file:///data/data/com.devstation.android/files/secret.txt",
            "content://media/external/images",
            "data:text/html,<h1>x</h1>",
            "javascript:alert(document.cookie)",
            "intent://scan/#Intent;scheme=http;end",
            "blob:https://example.com/uuid",
            "ftp://example.com/file",
            "ws://example.com/socket",
            "wss://example.com/socket",
            "custom://deep-link",
            "market://details?id=com.devstation.android",
            "tel:+1234567890",
            "mailto:user@example.com",
            "sms:+1234567890",
            "geo:0,0?q=location",
            "about:blank"
        )) {
            val d = policy.evaluateNavigation(url)
            assertEquals(url, BrowserSecurityPolicy.NavigationKind.BLOCKED, d.kind)
        }
    }

    @Test
    fun `scheme case-insensitivity blocks dangerous schemes in uppercase or mixed case`() {
        for (url in listOf(
            "FILE:///etc/passwd",
            "File:///data/data/secret",
            "JAVASCRIPT:alert(1)",
            "Data:text/html,<h1>test</h1>",
            "Ws://example.com/socket",
            "WSS://example.com/socket"
        )) {
            val d = policy.evaluateNavigation(url)
            assertEquals(url, BrowserSecurityPolicy.NavigationKind.BLOCKED, d.kind)
        }
    }

    // ---- §58.16 invalid URL ----

    @Test
    fun `invalid urls fail closed`() {
        for (url in listOf("", "not a url", "http://", "://missing", "http://sp ce.com")) {
            assertEquals(url, BrowserSecurityPolicy.NavigationKind.BLOCKED, policy.evaluateNavigation(url).kind)
        }
    }

    // ---- §60 SSRF: unusual host representations must not be loopback-credentialed ----

    @Test
    fun `localhost spoofing via suffix is NOT loopback`() {
        val d = policy.evaluateNavigation("http://localhost.evil.example/")
        assertNotEquals(BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, d.kind)
    }

    @Test
    fun `decimal and octal ipv4 loopback forms resolve to LOCAL_PREVIEW classification not bypass`() {
        // 2130706433 == 127.0.0.1; 0177.0.0.1 == octal loopback. Real parsing must see loopback,
        // so it lands in LOCAL_PREVIEW — never silently treated as ordinary internet.
        val decimal = policy.evaluateNavigation("http://2130706433/")
        val octal = policy.evaluateNavigation("http://0177.0.0.1/")
        assertEquals(BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, decimal.kind)
        assertEquals(BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, octal.kind)
    }

    @Test
    fun `hex loopback form classified as local`() {
        val d = policy.evaluateNavigation("http://0x7f000001/")
        assertEquals(BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, d.kind)
    }

    @Test
    fun `ipv6 mapped loopback classified as local`() {
        val d = policy.evaluateNavigation("http://[::ffff:127.0.0.1]:3000/")
        assertEquals(BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, d.kind)
    }

    @Test
    fun `private ipv4 ranges are treated with warning not public trust`() {
        for (host in listOf("192.168.1.1", "10.0.0.1", "172.16.0.1", "169.254.169.254")) {
            val d = policy.evaluateNavigation("http://$host/")
            assertTrue(host, d.kind == BrowserSecurityPolicy.NavigationKind.EXTERNAL_HTTP || d.kind == BrowserSecurityPolicy.NavigationKind.BLOCKED)
            assertNotEquals(host, BrowserSecurityState.SECURE, d.securityState)
        }
    }

    @Test
    fun `metadata-style link-local is flagged`() {
        val d = policy.evaluateNavigation("http://169.254.169.254/latest/meta-data/")
        assertNotEquals(BrowserSecurityState.SECURE, d.securityState)
    }

    @Test
    fun `public numeric decimal and hex ips are classified as internet and not loopback`() {
        // 16843009 == 1.1.1.1; 0x01010101 == 1.1.1.1; 134744072 == 8.8.8.8
        for (url in listOf("http://16843009/", "http://0x01010101/", "http://134744072/")) {
            val d = policy.evaluateNavigation(url)
            assertNotEquals("numeric public IP must not be LOCAL_PREVIEW: $url",
                BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, d.kind)
            assertNotEquals("numeric public IP must not be SECURE: $url",
                BrowserSecurityState.SECURE, d.securityState)
        }
    }

    @Test
    fun `localhost spoofing hostnames never gain LOCAL_PREVIEW classification`() {
        for (host in listOf("127.0.0.1.example.com", "evil-localhost.example", "localhost.evil.com", "127.0.0.1.nip.io")) {
            val d = policy.evaluateNavigation("http://$host/")
            assertNotEquals("spoofed host $host must not be LOCAL_PREVIEW",
                BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, d.kind)
        }
    }

    // ---- §58.12/§58.13 redirects (§7/§16) ----

    @Test
    fun `redirect is re-evaluated independently`() {
        val d = policy.evaluateRedirect("https://approved.example/start", "http://evil.example/steal")
        assertNotEquals(BrowserSecurityState.SECURE, d.securityState)
    }

    @Test
    fun `redirect to dangerous scheme is blocked`() {
        val d = policy.evaluateRedirect("https://approved.example/start", "file:///etc/passwd")
        assertEquals(BrowserSecurityPolicy.NavigationKind.BLOCKED, d.kind)
    }

    @Test
    fun `redirect to localhost from external page stays LOCAL_PREVIEW class not SECURE`() {
        val d = policy.evaluateRedirect("https://evil.example/", "http://127.0.0.1:3000/admin")
        assertEquals(BrowserSecurityPolicy.NavigationKind.LOCAL_PREVIEW, d.kind)
        assertEquals(BrowserSecurityState.LOCAL_PREVIEW, d.securityState)
    }

    @Test
    fun `relative redirect resolves against original`() {
        val d = policy.evaluateRedirect("https://approved.example/a/b", "../safe")
        assertEquals(BrowserSecurityPolicy.NavigationKind.EXTERNAL_HTTPS, d.kind)
    }

    // ---- §39/§40 host classification internals ----

    @Test
    fun `classifyHost uses parsed IPs`() {
        assertEquals(NetworkIntent.LOCAL_NETWORK, policy.classifyHost("127.0.0.1"))
        assertEquals(NetworkIntent.LOCAL_NETWORK, policy.classifyHost("localhost"))
        assertEquals(NetworkIntent.LOCAL_NETWORK, policy.classifyHost("::1"))
        assertEquals(NetworkIntent.LOCAL_NETWORK, policy.classifyHost("192.168.0.1"))
        assertEquals(NetworkIntent.INTERNET, policy.classifyHost("example.com"))
    }

    // ---- §64 download policy ----

    @Test
    fun `safe download allowed with sanitized name`() {
        val d = policy.evaluateDownload(
            DownloadRequest(url = "https://example.com/report.pdf", mimeType = "application/pdf", contentLength = 1_000)
        )
        assertTrue(d is DownloadDecision.Allowed)
        assertEquals("report.pdf", (d as DownloadDecision.Allowed).suggestedName)
    }

    @Test
    fun `path traversal filename rejected`() {
        val d = policy.evaluateDownload(
            DownloadRequest(url = "https://example.com/x", contentDisposition = "attachment; filename=\"../../.env\"", mimeType = "text/plain", contentLength = 10)
        )
        assertTrue(d is DownloadDecision.Rejected)
    }

    @Test
    fun `executable extensions rejected`() {
        for (name in listOf(
            "setup.exe", "script.sh", "app.apk", "run.bat", "lib.so", "pwned.msi",
            "payload.dll", "malware.dex", "hack.cmd", "danger.ps1", "script.vbs", "app.scr", "old.com"
        )) {
            val d = policy.evaluateDownload(
                DownloadRequest(url = "https://example.com/$name", mimeType = "application/octet-stream", contentLength = 10)
            )
            assertTrue(name, d is DownloadDecision.Rejected)
        }
    }

    @Test
    fun `oversized download rejected`() {
        val d = policy.evaluateDownload(
            DownloadRequest(url = "https://example.com/big.zip", mimeType = "application/zip", contentLength = 500L * 1024 * 1024)
        )
        assertTrue(d is DownloadDecision.Rejected)
    }

    @Test
    fun `download from dangerous scheme rejected`() {
        val d = policy.evaluateDownload(
            DownloadRequest(url = "file:///sdcard/evil.apk", mimeType = "application/vnd.android.package-archive", contentLength = 10)
        )
        assertTrue(d is DownloadDecision.Rejected)
    }

    @Test
    fun `download from dangerous scheme rejected even with benign extension`() {
        for (url in listOf(
            "file:///etc/hosts",
            "file:///data/data/com.devstation.android/files/secret.txt",
            "content://media/external/doc.pdf",
            "data:text/plain,hello",
            "javascript:void(0)"
        )) {
            val d = policy.evaluateDownload(
                DownloadRequest(url = url, mimeType = "text/plain", contentLength = 10)
            )
            assertTrue("download from $url must be rejected", d is DownloadDecision.Rejected)
        }
    }

    // ---- §46 security indicator honesty ----

    @Test
    fun `localhost is never reported as SECURE`() {
        for (url in listOf("http://localhost:3000", "http://127.0.0.1:8080", "http://[::1]/")) {
            assertNotEquals(BrowserSecurityState.SECURE, policy.securityStateFor(url))
        }
    }

    @Test
    fun `userinfo and query are redacted in display urls`() {
        val d = policy.evaluateNavigation("https://user:secret@example.com/path?token=abc123&api_key=xyz")
        assertTrue(!d.displayUrl.contains("secret"))
        assertTrue(!d.displayUrl.contains("abc123"))
    }
}
