# Phase 9 Implementation Plan
# Browser + Live Preview

## 1. Architecture Audit Result

Existing integration points (all reused, nothing duplicated):

| Need | Existing component |
|---|---|
| Secure process execution, sanitized env, bounded output, ownership, timeout | `BaseProcessCommandRunner` pattern (`TerminalTools.kt`), `LinuxCommandRunner`/`AndroidShellCommandRunner`, `AgentProcessRegistry` |
| Command security | `TerminalSecurityPolicy.assess()` — preview commands pass through the same gate; `LOCAL_NETWORK` category already exists |
| Path containment | `FilesystemSandbox` / `PathSandbox` |
| Network/host classification | `NetworkSecurityPolicy` (`classifyHost`, `LOCAL_NETWORK` vs `INTERNET`, URL redaction) |
| Audit | `SecurityAuditLogger` + `SecurityEventType` |
| Diagnostics | `SecurityDiagnostics` check pattern |
| Agent tools | `Tool`/`ToolDefinition`/`ToolContext` + `ToolRegistry` via `DefaultAgentToolFactory(mcpTools=…)` seam |
| Browser | Android `WebView` (platform, no new dependency) |

## 2. Phase 9 Architecture

```
Project → PreviewServerManager → CommandRunner (existing pattern) → localhost:<port>
                                        ↓ (launch gate)
                              TerminalSecurityPolicy → approval chain (CHECK→AUTHORIZE→REVALIDATE→EXECUTE→AUDIT)
Browser (WebView) ← BrowserSecurityPolicy (URL scheme/host validation, SSRF, downloads)
Preview tools → ToolRegistry → ToolExecutor → SecurityPolicyEngine (like any tool)
```

## 3. New Package `core/preview/`

| Component | Responsibility |
|---|---|
| `PreviewModels.kt` | `PreviewServer` (id, projectId, command, args, workingDir, port, host, state, owner, startedAt, restartCount, lastError), `PreviewState` (STOPPED/STARTING/RUNNING/STOPPING/FAILED/CRASHED), `BrowserTab`, `BrowserConsoleEntry`, `PreviewLogEntry`, `DownloadRequest` |
| `BrowserSecurityPolicy.kt` | URL validation: scheme allow-list (`http`/`https` only), proper `URI` host parsing (no prefix matching), localhost = exact host `localhost`/`127.0.0.1`/`::1` via parsed IP handling (rejects `localhost.evil.example`, decimal/octal/hex IPv4 tricks, `127.1`, `[::ffff:127.0.0.1]` surprises classified correctly), private/LAN classification via `NetworkSecurityPolicy`, redirect re-validation, download filename/MIME/size policy |
| `PreviewPortManager.kt` | free-port probe (`ServerSocket` bind test), ownership map port→(projectId, serverId), conflict detection, release |
| `PreviewServerManager.kt` | lifecycle STARTING→READY detection (port probe + HTTP health check + bounded 30 s timeout), running via the existing `BaseProcessCommandRunner` pattern with its own registry keyed by serverId, bounded redacted logs, restart limits (max 3, no silent infinite restart), project isolation (only the owning project's session may stop/access a server), crash detection, audit |
| `BrowserConsoleManager.kt` | bounded console entries (500 max), size cap, redaction via `SecretRedactor` |

## 4. Security Boundaries

* **Preview command**: `TerminalSecurityPolicy.assess(command, projectRoot, guest)`; blocked → refuse.
  Working dir resolved through `FilesystemSandbox` (no traversal/symlink/cross-project). Environment
  sanitized like the agent shell (secret names rejected). Binding: default `127.0.0.1`; `0.0.0.0` or
  LAN bind requires explicit approval flag at the manager API (never set by agent tools).
* **Browser**: WebView with `allowFileAccess=false`, `allowUniversalAccessFromFileURLs=false`,
  `allowContentAccess=false`, `javaScriptEnabled=true` (required for dev preview), `domStorageEnabled=true`,
  `mixedContent=NEVER_ALLOW`, `mediaPlaybackRequiresUserGesture=true`, safe-browsing on.
  `shouldOverrideUrlLoading` runs `BrowserSecurityPolicy` — dangerous schemes and unclassified hosts
  blocked; redirects re-validated. No JS bridge is exposed (§35: nothing needed; none = no attack
  surface). Downloads: no auto-open, filename sanitized (no path separators/`..`), size cap,
  destination app-external files dir only, confirmation dialog.
* **Permissions**: `onPermissionRequest` → always deny (§45 default).
* **Cross-project**: browser navigation to a preview port checks ownership when the tab belongs to a
  project session; preview manager refuses stop/log/status from a foreign project.

## 5. Browser UI (`feature/browser/BrowserScreen.kt`)

Top bar: back/forward/reload/stop, address field, security indicator chip (SECURE / LOCAL PREVIEW /
HTTP / BLOCKED), tab count button, menu (new tab, close tab, close others, console, clear data).
Tabs limited to 8. Error view for load failures with "Start Preview" affordance for localhost when
the owning server is not running. Console bottom sheet. No persistent history storage beyond
WebView's own back/forward list (§43).

## 6. Preview UI (`feature/preview/PreviewScreen.kt`)

Server card (status/port/URL/command), Start/Stop/Restart/Open/Copy URL, bounded console with
search/clear/auto-scroll/pause, readiness errors. Embedded WebView preview pane.

## 7. Agent Integration

Five tools in `core/agent/tools/PreviewTools.kt`, registered via the tool factory seam like MCP:
`start_preview` (ALWAYS_ASK, LOCAL_NETWORK intent), `stop_preview` (ASK), `restart_preview`
(ALWAYS_ASK), `get_preview_status` (ALLOW), `get_preview_logs` (ALLOW). All delegate to
`PreviewServerManager`; the manager enforces project containment and the terminal policy, so the
agent has no direct process access and cannot bind `0.0.0.0`.

## 8. Database

No Room migration. Preview configuration is per-process runtime state (servers die with the app
process by design — §56); per-project preview settings persist later if a real need emerges.
Remains Room v6.

## 9. Tests

| Suite | Coverage |
|---|---|
| `BrowserSecurityPolicyTest` | §58/§60: schemes, host parsing (spoofing, decimal/octal/hex IPv4, IPv6 forms, link-local, metadata), redirects, downloads |
| `PreviewPortManagerTest` | allocation, conflict, ownership, release |
| `PreviewServerManagerTest` | §57: lifecycle with a real local HTTP server process (python3/bash), readiness, timeout, crash, isolation, env sanitization, output limits, cancellation |
| `PreviewSecurityAttackTest` | §59/§61: cross-project access, hostile page content treated as data, agent cannot bind LAN, revoked grants |

Device tests: not available in this environment — documented, not claimed.

## 10. Non-Goals

Git/GitHub, deployment, VPS/SSH, Docker, remote execution, background agents, desktop/external
monitor modes. Phase 10 not started.
