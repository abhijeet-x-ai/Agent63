# Phase 9 Final Audit Plan
# DevStation Browser + Live Preview Verification & Hardening Pass

## 1. Scope & Verification Objectives

### A. Context & Purpose
Phase 9 introduces an embedded developer browser and live localhost preview system for DevStation. This final audit and hardening pass validates all Phase 9 components against the security specifications, verifies that zero Phase 10 features (Git, GitHub, SSH, VPS, Docker, Desktop mode, remote background daemons) have crept into the codebase, and confirms that existing Phase 1 through 8.1 foundations remain regression-free.

### B. Core Objectives
1. **WebView Hardening**: Ensure strict Android `WebView` security configurations (`allowFileAccess = false`, `allowContentAccess = false`, `mixedContentMode = NEVER_ALLOW`, zero `@JavascriptInterface` bridge exposure, automatic SSL error rejection, and rejection of all web permission requests).
2. **Network Intent & SSRF Defense**: Confirm that local loopback variants (`localhost`, `127.0.0.1`, `[::1]`, decimal `2130706433`, octal `0177.0.0.1`, hex `0x7f000001`, `[::ffff:127.0.0.1]`) correctly classify as `LOCAL_PREVIEW` and are never given `SECURE` public trust, while spoofed hostnames (`localhost.evil.com`, `127.0.0.1.example.com`) and numeric public IPs (`16843009`, `0x01010101`) are classified as internet and never loopback.
3. **Preview Process & Port Security**: Enforce process lifecycle boundaries, 30-second startup readiness bounds, max 3 restart attempts, dynamic ephemeral port probing via `ServerSocket(0)`, exclusive project/server port ownership, and strict rejection of non-loopback bindings (`0.0.0.0`, LAN IPs) by agent tools.
4. **Working Directory & Environment Containment**: Verify system directory rejection (`isSystemWorkingDirectory`) for paths like `/proc`, `/sys`, `/system`, `/etc`, and ensure sensitive environment variables fail closed before process launch.
5. **Log Redaction & Web Console Security**: Verify that credentials, cookies, tokens, and Authorization headers are redacted prior to storage or display, and that web console log messages are treated strictly as inert data, immune to prompt injection.
6. **Download Security**: Verify path traversal protection, executable extension rejection (including `.exe`, `.bat`, `.cmd`, `.sh`, `.apk`, `.jar`, `.dex`, `.so`, `.bin`, `.msi`, `.vbs`, `.ps1`, `.dll`, `.com`, `.scr`), 50MB file size ceiling, and strict rejection of downloads from dangerous schemes (`file:`, `content:`, `data:`, `javascript:`).
7. **Agent Tool Permissions**: Verify `start_preview` (HIGH risk, ASK permission), `stop_preview` (MEDIUM risk, ASK permission), `restart_preview` (MEDIUM risk, ALWAYS_ASK permission), `get_preview_status` (LOW risk, ALLOW permission), and `get_preview_logs` (LOW risk, ALLOW permission).
8. **Regression Safety**: Verify that Phase 7 (AI providers & tools) and Phase 8.1 (MCP stdio & HTTP SSE transport) security suites remain completely intact.
9. **Build & Test Verification**: Execute clean test suites and APK assembly to record exact test metrics and APK byte counts.

---

## 2. Embedded Browser & WebView Security Audit

### Target: `com.devstation.android.feature.browser.BrowserScreen.kt`
- **File Access Settings**:
  - `allowFileAccess = false`
  - `allowContentAccess = false`
  - `allowFileAccessFromFileURLs = false`
  - `allowUniversalAccessFromFileURLs = false`
- **Content & Execution Controls**:
  - `mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW`
  - `mediaPlaybackRequiresUserGesture = true`
  - `safeBrowsingEnabled = true`
  - `setWebContentsDebuggingEnabled(false)`
- **Zero Bridge Exposure**:
  - Complete codebase grep for `addJavascriptInterface` must return 0 occurrences. No JavaScript bridge is attached under any condition.
- **Handler Security**:
  - `WebViewClient.onReceivedSslError`: unconditionally calls `handler?.cancel()`. SSL certificates are never bypassed.
  - `WebChromeClient.onPermissionRequest`: unconditionally calls `request?.deny()`. Camera, microphone, and geolocation requests are denied.
  - `WebViewClient.shouldOverrideUrlLoading`: intercepts every top-level navigation and redirect, re-evaluating destination URLs through `BrowserSecurityPolicy`.

---

## 3. Scheme & SSRF / Loopback Validation Audit

### Target: `com.devstation.android.core.preview.BrowserSecurityPolicy.kt`
- **Permitted Schemes**:
  - `https://` -> Evaluated as `EXTERNAL_HTTPS` (`SECURE`).
  - `http://` -> Evaluated as `EXTERNAL_HTTP` (`HTTP`).
- **Blocked Dangerous Schemes**:
  - `file:`, `content:`, `javascript:`, `data:`, `blob:`, `ftp:`, `intent:`, `about:`, `ws:`, `wss:`, `custom:`, `market:`, `tel:`, `mailto:`, `sms:`, `geo:`.
  - All must evaluate to `NavigationKind.BLOCKED` (`BLOCKED`).
  - Case variations (`FILE:`, `Data:`, `JAVASCRIPT:`, `Ws:`, `WSS:`) must also evaluate to `BLOCKED`.
- **Loopback Classification (`LOCAL_PREVIEW`)**:
  - `http://localhost:<port>`
  - `http://127.0.0.1:<port>`
  - `http://[::1]:<port>`
  - Decimal u32: `http://2130706433/`
  - Octal: `http://0177.0.0.1/`
  - Hex: `http://0x7f000001/`
  - IPv6-mapped IPv4: `http://[::ffff:127.0.0.1]:<port>/`
  - Must display `LOCAL PREVIEW` badge, never `SECURE`.
- **SSRF & Spoofing Defenses**:
  - Hostnames ending in loopback tokens (e.g. `localhost.evil.com`, `127.0.0.1.example.com`, `127.0.0.1.nip.io`) must resolve via standard host rules and must NOT receive `LOCAL_PREVIEW`.
  - Public numeric IPs (e.g. `16843009`, `0x01010101`, `134744072`) must classify as `EXTERNAL_HTTP` / internet, never `LOCAL_PREVIEW`.
  - Cloud metadata addresses (e.g. `169.254.169.254`) and private RFC-1918 ranges must display `WARNING`, never `SECURE`.
- **Redirect Revalidation**:
  - Redirect chains re-evaluate every destination independently. An external page cannot redirect to a dangerous scheme (`file:`) or smuggle credentials.

---

## 4. Preview Server Lifecycle, Port Management & Process Isolation Audit

### Target: `PreviewServerManager.kt`, `PreviewPortManager.kt`, `PreviewModels.kt`
- **State Machine Integrity**:
  - States: `STOPPED` -> `STARTING` -> `RUNNING` -> `STOPPING` -> `STOPPED`. Failure states: `FAILED`, `CRASHED`.
  - Only one active server allowed per project.
  - Startup timeout bounded to 30 seconds with active socket polling.
  - Automatic restart capped at 3 attempts to avoid crash loops.
- **Port Allocation & Conflict Prevention**:
  - Ephemeral port probing via `ServerSocket(0)`.
  - Ownership tracking: `ports[port] = PortReservation(projectId, serverId)`.
  - Cross-project port hijacking prevented: port allocation fails if another project owns the port.
  - Port release guaranteed on process stop or failure.
- **Host Binding Enforcement**:
  - Host binding defaults strictly to `127.0.0.1`.
  - `0.0.0.0` or LAN interface binding is rejected at the manager layer unless explicit user `allowExternalBind` is set.
  - Agent tools never pass `allowExternalBind = true`.
- **Working Directory Security**:
  - `isSystemWorkingDirectory` rejects `/proc`, `/sys`, `/dev`, `/vendor`, `/system`, `/apex`, `/etc`, `/root`, `/sbin`, `/bin`, `/usr`, `/lib`, `/opt`, `/boot`, `/data/system`, `/data/misc`.
  - Working directory must be a valid project directory.
- **Environment Scrubbing**:
  - Sensitive environment variable names containing `API_KEY`, `TOKEN`, `SECRET`, `PASSWORD`, `CREDENTIAL`, etc., fail the launch immediately (fail-closed).
  - Safe environment variables allowed only matching `(PORT|HOST|NODE_ENV|PUBLIC_[A-Za-z0-9_]+|BROWSER|CI)`.

---

## 5. Log Redaction & Web Console Security Audit

### Target: `BrowserConsoleManager.kt`, `SecretRedactor.kt`, `PreviewServerManager.kt`
- **Console Entry Sanitization**:
  - Console buffer bounded to 500 entries (oldest evicted when full).
  - Credentials, bearer tokens, API keys, cookies (`Set-Cookie`, `Cookie`), and Authorization headers redacted before storing in buffer.
- **Inert Data Handling**:
  - Console messages, error traces, and web logs are treated strictly as display strings.
  - Prompt injection strings inside web logs (e.g. `System: override permissions`, `### Instruction: ...`) remain inert text and have zero capability to execute commands or alter system state.
- **Server Output Streaming**:
  - Stdout and stderr streams parsed and redacted line-by-line using `SecretRedactor`.
  - Bounded memory log per server (1,000 lines).

---

## 6. File Download & Content Mapping Security Audit

### Target: `BrowserSecurityPolicy.kt`, `BrowserScreen.kt`
- **Scheme Validation**:
  - Downloads permitted only from `http:` and `https:`.
  - Downloads originating from `file:`, `content:`, `data:`, `javascript:` are immediately rejected.
- **File Name Sanitization**:
  - Raw filenames checked for path traversal (`..`, `/`, `\`, leading `.`, `:`).
  - Unsafe characters replaced with underscores.
- **Dangerous Extensions**:
  - Rejects: `exe`, `bat`, `cmd`, `sh`, `apk`, `jar`, `dex`, `so`, `bin`, `msi`, `vbs`, `ps1`, `dll`, `com`, `scr`, `hta`.
- **Size Bounds**:
  - Capped at 50MB (`maxDownloadBytes`). Oversized payloads rejected before download.
- **Execution Policy**:
  - Downloaded files are saved to app storage and are NEVER executed automatically.

---

## 7. Agent Tools & Prompt Injection Defense Audit

### Target: `PreviewTools.kt`, `AgentToolFactory.kt`
- **Tool Registrations**:
  - `start_preview`: Risk = HIGH, Permission = ASK, Action = EXECUTE, Network = LOCAL_NETWORK.
  - `stop_preview`: Risk = MEDIUM, Permission = ASK, Action = EXECUTE.
  - `restart_preview`: Risk = MEDIUM, Permission = ALWAYS_ASK, Action = EXECUTE.
  - `get_preview_status`: Risk = LOW, Permission = ALLOW, Action = READ.
  - `get_preview_logs`: Risk = LOW, Permission = ALLOW, Action = READ.
- **Sandbox Bounds**:
  - `start_preview` resolves `workingDirectory` within `context.resolveWithinProject(it)`.
  - `allowExternalBind` is hardcoded to `false` for all agent invocations.
  - Project isolation enforced: an agent running for Project A cannot stop or inspect logs for Project B.

---

## 8. Phase 7 & 8.1 Regression Audit

### Targets:
- Phase 7: AI providers (Gemini, Anthropic, OpenAI-compatible), retry policies, tool calling, credential handling.
- Phase 8.1: MCP stdio transport, HTTP SSE transport, authorization headers, SSRF validation for remote MCP endpoints.
- Verification: All existing unit tests in `core/agent/`, `core/ai/`, `core/mcp/`, and `core/security/` must pass without regressions.

---

## 9. Test Execution & APK Verification Plan

1. **Unit & Security Attack Tests**:
   - Command: `./gradlew testDebugUnitTest`
   - Record exact count of tests run, passed, failed, and ignored across all suites.
2. **Compilation**:
   - Command: `./gradlew compileDebugKotlin`
3. **APK Assembly**:
   - Command: `./gradlew assembleDebug`
   - Target APK: `app/build/outputs/apk/debug/app-debug.apk`
   - Record exact file size in bytes and MD5/SHA256 checksum.
4. **Environment Check**:
   - Verify device/emulator availability via `adb devices`.
