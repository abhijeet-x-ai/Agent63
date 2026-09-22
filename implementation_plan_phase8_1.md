# Phase 8.1 Implementation Plan
# MCP Production Hardening + Completion

## 1. Current MCP Architecture (audited)

```
AgentRuntime → ToolRegistry (built-ins + McpToolWrapper tools)
                     ↓
              ToolExecutor → SecurityPolicyEngine → Approval → Revalidate → Execute → Audit
                     ↓ (for MCP tools only)
              McpToolWrapper → McpClient → McpTransport (InMemoryMcpTransport only)
```

* `McpTransport` interface: `connect(config) / send(connection, request) / close(connection)`, plus
  `MAX_MCP_RESPONSE_CHARS` (256 KB), connect timeout 15 s, request timeout 30 s.
* `McpServerManager` owns lifecycle (register/update/enable/connect/disconnect/refresh/remove) with
  a per-server connect `Mutex`; capabilities go through `McpSecurityClassifier` (UNKNOWN → ALWAYS_ASK).
* `McpToolWrapper` puts MCP tools in the same `ToolRegistry`; no bypass exists.
* Room v6 (`mcp_servers`, `mcp_capabilities`, `skills`, `agent_profiles`) persists config; sensitive
  env names are rejected at registration; `credentialReferenceId` points at the Keystore store.

### Missing production pieces (this phase)

| Gap | Fix |
|---|---|
| No real STDIO transport | `McpStdioTransport` — controlled `ProcessBuilder`, argv array (no `sh -c`), sanitized env, bounded stdout/stderr, request correlation by JSON-RPC id, startup/init timeouts, graceful→forced shutdown, ownership registry keyed by server id |
| No real HTTP transport | `McpHttpTransport` — OkHttp POST JSON-RPC, HTTPS-by-default URL validation, `followRedirects(false)` + per-redirect re-validation through `NetworkSecurityPolicy`, response size caps, timeouts, cancellation |
| Detail/editor UI missing | `McpServerDetailScreen` (+ edit dialog), `SkillDetailScreen` (+ editor), `AgentBuilderScreen` (+ effective security view) |
| Diagnostics | extend `SecurityDiagnostics` with STDIO/HTTP transport checks |
| Tests | transport security suites (STDIO §37, HTTP §38), MCP attack tests §39–§41, revalidation tests §40 |

## 2. STDIO Security Design

* **Launch gate**: every STDIO command is assessed with the existing `TerminalSecurityPolicy`
  (`assess(command, null, guest=true)`). Commands whose `blockedReason != null` are refused. The
  assessment's category is recorded in the audit event at launch. Structural checks on top:
  argv-array execution only (never `sh -c`), executable and arguments length-bounded.
* **Working directory**: the transport receives one app-controlled directory at construction
  (DevStation's private files dir). It validates the canonical path is not an Android-private/system
  location outside the app (`/data/misc`, `/proc`, `/sys`, `/dev`, `/vendor`, `/system`, `/apex`,
  `/data/misc/keystore`, other apps' `/data/...`). Agent file tools remain governed by
  `FilesystemSandbox` exactly as before — the MCP server process itself only ever runs in the
  app-provided directory; it is never given a project root automatically.
* **Environment isolation**: the process environment is cleared; only `PATH`, `HOME` and the config's
  explicitly-approved, non-sensitive variables are set (secret-looking names are rejected again at
  launch even if a config was written by an older build).
* **Limits**: startup timeout 10 s, per-request timeout 30 s, stdout/stderr caps (256 KB each),
  concurrent-request cap 8, duplicate-connect prevented (per-server mutex already in manager +
  transport-level single session per server).
* **Ownership/cleanup**: `McpProcessRegistry` keyed by server id; `close()` → destroy() →
  destroyForcibly(); disable/remove/disconnect all terminate. Only the transport that owns the
  session can kill it — unrelated agents/tools cannot reach the registry.
* **Cancellation**: request waits are `suspendCancellableCoroutine`-based; cancelling the caller
  removes the pending entry and closes nothing else; STOP-AGENT propagates via the existing chain.

## 3. HTTP Security Design

* **URL validation**: `java.net.URI` parse + scheme allow-list (`https` always; `http` only for
  loopback). `file:`/`content:`/`data:`/`javascript:`/`intent:` and every custom scheme rejected.
  Host classified via existing `NetworkSecurityPolicy.classifyHost` — destination intent is audited.
* **Redirects**: OkHttp client built with `followRedirects(false)`; each 3xx Location is re-validated
  as a fresh destination (scheme + host class). A redirect from approved → denied host is refused;
  the original approval is never inherited.
* **Response security**: status must be 2xx; `Content-Type` must be JSON or text; body read with a
  hard byte cap; JSON parse failures degrade to a `McpTransportException` (never passed raw into the
  agent context); the existing `McpClient` layer redacts/bounds output before agent exposure.
* **Limits**: connect 15 s, read 30 s, max response 256 KB, max header count via OkHttp defaults,
  concurrent requests bounded by an in-transport semaphore (8), cancellation cancels the OkHttp call.

## 4. UI Completion

| Screen | Content |
|---|---|
| `McpServerDetailScreen` | overview (transport, endpoint/command, project scope, security mode), live status, capabilities grouped Tools/Resources/Prompts with security classification chips, security section (classification rules, env isolation note, credential-reference only), recent audited MCP activity, actions Connect/Disconnect/Enable/Disable/Refresh/Remove/Edit (dialog) |
| Add/Edit server dialog | name, description, transport, command/args (STDIO), endpoint (HTTP, validated), enabled, autoConnect, non-secret env entries; secret-looking names rejected with explanation |
| `SkillDetailScreen` | identity, version/author, instructions, required tools, **requested permissions vs effective permissions** (explicit distinction), security mode, usage, actions Run (project picker)/Enable/Disable/Duplicate/Delete/Edit (USER skills only) |
| Skill editor dialog | name/description/version/instructions/required tools/capabilities; validated before save; security-bypass declarations rejected |
| `AgentBuilderScreen` | sections Identity / Instructions / Provider / Model / Tools / Skills / MCP / Security / Limits; validation errors surfaced; **effective security summary** card (mode, tools/skills/MCP counts, what still requires approval) |
| Nav graph | `McpServerDetail`, `SkillDetail`, `AgentBuilder` destinations wired; add-server / create-skill / click-through flows connected |

## 5. Diagnostics Extension

`SecurityDiagnostics` gains transport checks (executed, not asserted):
`mcp_stdio_command_validation` (blocked-classification commands are refused), `mcp_stdio_env_isolation`
(sanitized env contains no secret-looking keys and never inherits host env), `mcp_http_url_validation`
(HTTPS enforced, dangerous schemes refused, loopback policy), `mcp_request_correlation` (a response
for id A cannot complete request B).

## 6. Test Strategy

| Suite | Covers |
|---|---|
| `McpStdioTransportSecurityTest` | §37: invalid executable, unknown command → UNKNOWN handling, private/proc paths blocked, env isolation (no secret inheritance), oversized stdout/stderr, process timeout, cancellation, abnormal exit, duplicate start, orphan cleanup, ownership |
| `McpHttpTransportSecurityTest` (MockWebServer) | §38: malformed URL, unsupported schemes, plaintext HTTP off-loopback, redirect to denied host, oversized response, malformed JSON, invalid MCP structure, timeout, cancellation, auth-header never logged, concurrent request correlation |
| `McpAttackSuiteTest` | §39–§41: .env/credential path attempts still denied by engine, Android private paths, cross-project, flood protection (response cap), injection via tool description/prompt/resource treated as data, revoked permission → deny, approval-for-server-A ≠ server-B, project mismatch |
| Regression | all Phase 1–8 suites must pass unmodified |

Device testing: no emulator/device is available in this environment — documented as a limitation in
the build report (§49 of the spec), never claimed as performed.

## 7. Non-Goals

Phase 9 (browser, live preview, Git/GitHub, SSH/VPS, Docker, deployment, remote/background agents)
is not started. No Room migration (v6 schema is sufficient — no schema change required).

## 8. Files

**New**: `core/mcp/McpStdioTransport.kt`, `core/mcp/McpHttpTransport.kt`,
`feature/mcp/McpServerDetailScreen.kt`, `feature/skills/SkillDetailScreen.kt`,
`feature/agentprofiles/AgentBuilderScreen.kt`, plus tests.

**Modified**: `AppContainer.kt` (transport factory + DI), `SecurityDiagnostics.kt` (transport
checks), `DevStationNavGraph.kt`, `Screen.kt` (none needed — routes exist), `McpViewModels.kt`.
