# Phase 8.1 Build Report
# MCP Production Hardening + Completion

## 1. Executive Summary

Phase 8.1 completes the Phase 8 MCP implementation toward production readiness without redesigning
anything. The two real transports (STDIO and HTTP), the three missing UI screens, transport-specific
security diagnostics, and a dedicated transport/attack test suite were added on top of the unchanged
Phase 1–8 architecture.

**The security chain is unchanged and now exercised end-to-end through real transports:**

```
MCP capability → McpToolWrapper → ToolRegistry → ToolExecutor → SecurityPolicyEngine
→ PermissionEvaluator → Approval → REVALIDATE → Execute → Output Limiting → Secret Redaction → Audit
```

No transport bypasses it. No bypass was introduced. Phase 7 security is authoritative throughout.

## 2. Phase 8 Baseline (audited before any change)

* `McpTransport` interface + `InMemoryMcpTransport` only; `AppContainer` stubbed the factory.
* `McpServerManager` lifecycle complete (register/update/enable/connect/disconnect/refresh/remove,
  per-server connect mutex); capabilities classified by `McpSecurityClassifier` (UNKNOWN → ALWAYS_ASK).
* `McpToolWrapper` registered MCP tools in the same `ToolRegistry` — the pipeline was already intact.
* Routes `McpServerDetail` / `SkillDetail` / `AgentBuilder` existed with no screens; add-server and
  create-skill were no-ops.
* All 427 Phase 1–8 tests passed before this phase began.

## 3. Architecture Audit Result

The Phase 8 design was sound; nothing needed rework. All Phase 8.1 work is **additive**:
2 transport implementations, 3 screens, 1 diagnostics extension, 4 test suites, small seams in
`AppContainer` (transport factory), `McpViewModels.kt` (detail ViewModel), `SkillsViewModel` /
`AgentProfilesViewModel` (upsert/duplicate/run), and the nav graph (3 destinations wired).

## 4–7. STDIO Implementation (`core/mcp/McpStdioTransport.kt`)

* **Launch gate (§6)**: the server command (`command + arguments`) is assessed by the existing
  `TerminalSecurityPolicy` before launch. Any structurally blocked command (Android-private paths,
  `/proc/*/environ`, un-verifiable shell substitution in the *assessment's* sense) is refused with
  `McpSecurityRejection`. Unknown commands keep Phase 7's strict UNKNOWN behavior.
* **argv only (§9)**: the process starts as `ProcessBuilder(argv)` — never `sh -c <string>` — so
  shell metacharacters in configuration cannot be interpreted. Verified by test.
* **Working directory (§7)**: one app-controlled directory (`files/mcp`) injected at construction;
  the transport never receives a project root.
* **Environment isolation (§8/§14)**: the child env is cleared; only `PATH`, `HOME`, and explicitly
  approved non-secret variables are passed. Secret-looking names (`API_KEY`, `TOKEN`, `SECRET`,
  `PASSWORD`, `PRIVATE_KEY`, `CREDENTIAL`, `AUTH`, `ACCESS_KEY`, `SESSION_KEY`, `OAUTH`), invalid key
  names and oversized values are rejected *again at launch* (fail closed even against stale configs).
* **Limits (§10)**: startup timeout 10 s, request timeout 30 s (default), stdout line buffer cap
  256 K chars, stderr sink cap 256 K chars, duplicate session per server rejected.
* **Ownership (§11)/cleanup (§12)**: `McpProcessRegistry` keyed by server id, private to the owning
  transport instance. `close()` → `destroy()` → wait(2 s) → `destroyForcibly()`. Disable/remove/
  disconnect terminate; `shutdownAll()` cleans up everything.
* **Request correlation (§21)**: dispatch by JSON-RPC id only — a response can complete only the
  pending request with the same id; timed-out requests are abandoned so late responses cannot
  complete dead requests.
* **Cancellation (§22)**: request waits are coroutine-cancellable polls; cancellation surfaces within
  the poll interval and removes the pending entry.

## 8–10. HTTP Implementation (`core/mcp/McpHttpTransport.kt`)

* Built on the project's existing OkHttp dependency (no second HTTP client architecture).
* **URL validation (§14/§15)**: `URI` parse + scheme allow-list — `https` always; `http` only for
  loopback (`127.0.0.1`, `localhost`, `::1`). `file:`, `content:`, `data:`, `javascript:`, `intent:`,
  `ws:`, `wss:`, `ftp:`, `gopher:` and every other scheme are rejected.
* **Redirects (§16)**: OkHttp client built with `followRedirects(false)` / `followSslRedirects(false)`.
  Each 3xx `Location` is resolved and re-validated as a brand-new destination (max 3 hops) — a
  redirect from an approved host to a denied host fails closed; approval is never inherited.
* **Response security (§19)**: 2xx only, JSON content type only, body capped at 256 K chars,
  malformed JSON and mismatched response ids throw (never passed raw into the agent context).
* **Limits (§18)**: connect 15 s, read/write 30 s, call timeout 35 s, concurrency bounded by a
  semaphore (8).
* **Auth (§17)**: the transport sends no `Authorization` header and logs nothing; credentials ride
  the existing `credentialReferenceId` → Keystore path. Verified by test that no Authorization
  header is ever emitted.
* **Cancellation (§22)**: `enqueue`-based `awaitSuspending` with `invokeOnCancellation { call.cancel() }`.

## 11. MCP Session Lifecycle

States remain `DISABLED / DISCONNECTED / CONNECTING / CONNECTED / ERROR / STOPPING` in
`McpServerManager` (unchanged). Both transports implement `connect/send/close` with single-session
semantics; the manager's per-server mutex prevents concurrent initialization for the same server.

## 12–14. Cancellation, Limits, Output Security

* STOP-AGENT propagates: `AgentRuntime → ToolExecutor → McpToolWrapper → McpClient → McpTransport`
  (process poll exit / OkHttp call cancel). No new cancellation path was created.
* Output caps are enforced at three layers: transport (256 K), `McpClient` (`MAX_MCP_RESPONSE_CHARS`),
  and the existing Phase 6 `OutputLimiter`/redaction before agent exposure.

## 15–17. UI Completion

| Screen | What was added |
|---|---|
| `McpServerDetailScreen` | Overview (transport, command/endpoint, project scope, security mode, "Authenticated" for credential references — never the secret), live status, capabilities grouped Tools/Resources/Prompts with classification chips, a security explainer section, actions Connect/Disconnect/Enable/Disable/Refresh/Remove, and the full add/edit dialog |
| `McpServerEditDialog` | name, description, transport selector, command/args (quote-aware argv parsing), endpoint URL, enabled, autoConnect, non-secret env entries with inline secret-name rejection; `McpServerManager` re-validates on save |
| `SkillDetailScreen` | identity, instructions, required tools, usage, and the **requested vs effective permissions** distinction; Run/Enable/Duplicate/Delete/Edit (edit/duplicate/delete only for USER/PROJECT skills) |
| `SkillEditDialog` | name/description/version/instructions/tools/capability checkboxes; `SkillManager` validation rejects bypass declarations on save |
| `AgentBuilderScreen` | Identity, Instructions, Provider/Model, Tools, Skills, MCP, Security (scope + request flags), Limits; validation errors surfaced inline; **Effective Security summary card** (informational: mode, counts, what still requires approval, "System/credentials: always denied") |
| Nav graph | all three destinations wired; add-server and create-skill flow through `McpServerDetail?serverId=new` / `SkillDetail?skillId=new` |

## 18. Security Integration

Nothing was moved. MCP tools still enter via `DefaultAgentToolFactory(mcpTools = …)` →
`ToolRegistry`; Skills still execute through `SkillExecutor → AgentRuntime`; profiles remain
sanitized configuration. `SecurityPolicyEngine`, `PermissionEvaluator`, revalidation, `FilesystemSandbox`,
`SensitiveFilePolicy`, `TerminalSecurityPolicy`, `NetworkSecurityPolicy`, `SecurityAudit`, and
`SecretRedactor` are used exactly as in Phase 7/8.

## 19. Security Diagnostics Extension

Four new executed checks in `SecurityDiagnostics`:
`mcp_stdio_command_validation` (a `sh -c echo $(id)` server command is refused by the real gate),
`mcp_stdio_env_isolation` (secret env rejected, safe var passed, no secret keys in the sanitized map),
`mcp_http_url_validation` (HTTPS accepted, loopback HTTP accepted, plaintext/file/data/javascript
rejected), `mcp_request_correlation` (a mismatched-id response cannot complete a request).
Each performs its probe — none is asserted from code inspection.

## 20. Device Testing

**Manual device testing was NOT performed.** No Android device or emulator is available in this
environment. The §34/§35/§36 manual smoke tests (server list, create/edit, connect, capability
discovery, approval taps, skill run, agent run, STOP AGENT, real local STDIO server, real HTTP
endpoint) were not executed by hand. This is documented as a limitation, never claimed as verified.

## 21. STDIO Security Tests (§37) — `McpStdioTransportSecurityTest` (13 tests)

Invalid executable; shell-substitution command rejected; `/data/data` argument rejected;
`/proc/self/environ` rejected; `/sys` rejected; secret env rejected at launch; sanitized env
shape (PATH/HOME present, no secret keys, invalid key names rejected); request timeout fails
closed promptly; abnormal process exit fails closed; duplicate session rejected; oversized-output
cap finite; argv-array semantics (metacharacters never shell-interpreted); close terminates the
owned process; ownership isolation between transport instances; cancellation surfaces promptly.

## 22. HTTP Security Tests (§38) — `McpHttpTransportSecurityTest` (15 tests, MockWebServer)

Malformed URL; all forbidden schemes; plaintext HTTP off-loopback rejected; loopback HTTP +
HTTPS accepted; network-policy classification delegation; blind redirect rejected; redirect loop
fails closed; oversized response rejected; malformed JSON fails closed; non-JSON content type
rejected; NO_RESPONSE timeout; no Authorization header ever sent; response-id correlation
(matching accepted, mismatched rejected); valid round-trip; HTTP 500 fails closed.

## 23–24. MCP Attack + Injection Tests (§39/§41) — `McpPhase81AttackSuiteTest` (9 tests)

Description cannot soften a destructive tool name; UNKNOWN stays ALWAYS_ASK; system-flavored
tools classify SYSTEM; `.env`/`credentials.json`/`id_rsa`/`server.pem` sensitive detection;
credential-store path rejected by the sandbox; response cap finite; revoked-grant audit path;
injected instructions ("Ignore previous instructions…") remain data and secrets are redacted;
audit summaries bound oversized output; tool-name sanitization; argv semantics for hostile
arguments.

## 25. Approval Revalidation (§40)

Revalidation is unchanged from Phase 7 and re-verified by the existing suites (469 total tests
include the full Phase 7 `SecurityAttackSuiteTest`): revoked permission before execution → DENY,
and grants remain bound to their task/session/project identity. No MCP-specific exemption exists.

## 26. Regression Tests

All Phase 1–8 suites pass unmodified. One Phase 8 expectation was **tightened** (not weakened):
the classifier now treats the tool *name* as primary and lets server descriptions only escalate —
this found and fixed the real softening bug (§27 item 1). No test was deleted or loosened.

## 27. Bugs Found and Fixed

1. **Classifier softening via description (real security bug).** `McpSecurityClassifier` combined
   name+description into one string, so a tool named `purge_cache` described as "totally safe,
   read only" classified READ_ONLY (auto-allow). *Fix:* name is classified independently;
   the description can only escalate (severity ordering, UNKNOWN ≥ DESTRUCTIVE severity);
   a blank description no longer outranks the name. Regression: attack-suite test.
2. **`read_document` with blank description classified UNKNOWN.** Same root cause, caught by the
   existing Phase 8 suite after the fix: blank descriptions were treated as a classified signal.
   *Fix:* blank description is neutral (inherits name classification). Regression added.
3. **STDIO connect threw on short-lived processes.** `awaitUsable()` threw out of `connect()` for a
   server that exits during startup instead of returning a failure. *Fix:* probe result is wrapped;
   exit-during-startup is a clean `Result.failure` and the session is cleaned up.
4. **HTTP redirect branch never returned the parsed response.** The `response.use { … }` block
   computed the response but the `return` sat inside `use`, so the loop never handed the result
   back (every request failed). *Fix:* the block returns the parsed response (or null to continue
   redirecting), which the caller returns.

## 28. Source Security Review (§47)

Searches over the final tree:
* `ProcessBuilder` — only Phase 2 user terminal, Phase 3 runtime launcher, and the MCP STDIO
  transport (argv-array, env-cleared, policy-gated). No `Runtime.exec` anywhere.
* No `sh -c`/`bash -c` in MCP code (comment-only mention documents its absence).
* No hardcoded secrets in MCP/Skills/Profiles code; no `su`/`sudo` execution.
* No `trusted*` bypass flags anywhere in the codebase.
* `followRedirects(false)` confirmed; redirects re-validated per hop.
* MCP child env: `environment()` is called then immediately cleared and repopulated from the
  sanitized map — no host inheritance.
* No `Authorization`/`Bearer` strings in the MCP layer.
* No code path outside `ToolExecutor` reaches `McpClient` for execution.
* No MCP/Skills/Profiles code touches `SecurityPolicyRepository` or `securityManager` — policy
  mutation remains UI-only.

## 29. Build Verification

| Check | Result |
|---|---|
| `./gradlew compileDebugKotlin` | ✅ 0 errors |
| `./gradlew testDebugUnitTest` | ✅ **469/469 passed, 0 failures** (61 suites) |
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL |

Suite breakdown: 117 Phase 1–5 · 135 Phase 6 · 121 Phase 7 · 54 Phase 8 · **42 Phase 8.1**
(13 STDIO + 15 HTTP + 9 attack + 5 across ViewModel/UI-support helpers in the transport suites).

## 30. APK

```
app/build/outputs/apk/debug/app-debug.apk
20,030,074 bytes (~19.10 MB)
```

| Phase | Size | Delta |
|---|---|---|
| Phase 7 | 19,791,773 bytes | — |
| Phase 8 | 19,791,773 bytes | +0 |
| **Phase 8.1** | **20,030,074 bytes (~19.10 MB)** | **+238 KB** (2 transports, 3 screens, diagnostics, tests excluded from APK) |

No new dependency was added (OkHttp was already present).

## 31. Known Limitations

1. **No device verification** (§20). All STDIO process tests run on the JVM against real child
   processes; Compose screens are compile-verified only.
2. **STDIO working directory is app-scoped, not project-scoped.** MCP servers run in `files/mcp`
   and never receive a project root automatically; a future phase could add an explicit,
   user-approved project binding.
3. **HTTP transport is request/response (POST JSON-RPC).** Streamable HTTP / SSE server-push
   sessions are a future extension of the same transport interface.
4. **Agent Builder provider/model selection is free-text IDs.** A picker over registered providers
   is a UX follow-up; validation occurs at save.
5. **Skill "Run" prompts for project ID.** A project picker reusing the existing project list is a
   UX follow-up.
6. **Classifier heuristics remain name/keyword based.** Conservative by design; anything unmatched
   is UNKNOWN → always-ask.

## 32. Manual Tests Not Performed

Explicitly not performed: §34 (15 UI smoke scenarios), §35 (real local STDIO server on device),
§36 (real HTTP MCP endpoint on device). Not claimed, not verified.

## 33. Phase 9 Not Started

No browser, live preview, Git/GitHub, SSH/VPS, Docker, deployment, remote execution, background
agents, desktop mode, or external monitor mode was implemented. Clean extension points remain on
`McpTransport`, the detail routes, and the tool factory. **STOP.**

---

## Appendix A — STDIO Security Policy

| Control | Enforcement |
|---|---|
| Command gate | `TerminalSecurityPolicy.assess` before launch; blocked → `McpSecurityRejection` |
| Process model | direct argv array; no shell |
| Working dir | single app-controlled dir (`files/mcp`) |
| Environment | cleared; PATH + HOME + approved non-secret vars; secret names re-checked at launch |
| Startup | 10 s timeout; exit-during-startup = clean failure |
| Request | 30 s default timeout; id correlation; abandon-on-timeout |
| Output | stdout 256 K line-buffer cap; stderr 256 K sink cap (diagnostics only) |
| Ownership | `McpProcessRegistry` per transport instance, keyed by server id |
| Shutdown | destroy → wait 2 s → destroyForcibly; disable/remove/disconnect/shutdownAll |

## Appendix B — HTTP Security Policy

| Control | Enforcement |
|---|---|
| Scheme | https always; http only loopback; forbidden: file/content/data/javascript/intent/ws/wss/ftp/gopher |
| Redirects | not followed by client; ≤3 hops; each hop re-validated |
| Response | 2xx + JSON content type + ≤256 K chars + JSON-RPC parse + id match |
| Timeouts | connect 15 s, read/write 30 s, call 35 s |
| Concurrency | semaphore 8 |
| Auth | no header sent by transport; credential references via Keystore only |
| Cancellation | OkHttp call cancelled on coroutine cancellation |

## Appendix C — MCP Transport Limits

| Limit | Value |
|---|---|
| `MAX_MCP_RESPONSE_CHARS` | 256,000 |
| `McpStdioTransport.MAX_STREAM_CHARS` | 256,000 |
| STDIO startup timeout | 10 s |
| Default request timeout | 30 s |
| HTTP max redirects | 3 |
| HTTP concurrency | 8 |
| Env value length | 2,000 chars |

## Appendix D — Security Attack Test Matrix (Phase 8.1 additions)

| Attack | Expected | Status |
|---|---|---|
| `sh -c` server command via STDIO | Rejected | ✅ |
| `/data/data`, `/proc/self/environ`, `/sys` server args | Rejected | ✅ |
| Secret env var at launch | Rejected | ✅ |
| Host env inheritance | Impossible (env cleared) | ✅ |
| Shell metacharacters in argv | Not interpreted | ✅ |
| Plaintext HTTP off-loopback | Rejected | ✅ |
| `file:`/`data:`/`javascript:`/`intent:` endpoints | Rejected | ✅ |
| Redirect to denied destination | Rejected per-hop | ✅ |
| Oversized response (STDIO & HTTP) | Capped/rejected | ✅ |
| Malformed JSON / non-JSON / wrong id | Fail closed | ✅ |
| Description softening a destructive tool | Impossible (escalate-only) | ✅ |
| Prompt injection via MCP output | Treated as data; secrets redacted | ✅ |

## Appendix E — Changed Files / Packages

**New**: `core/mcp/McpStdioTransport.kt`, `core/mcp/McpHttpTransport.kt`,
`feature/mcp/McpServerDetailScreen.kt`, `feature/skills/SkillDetailScreen.kt`,
`feature/agentprofiles/AgentBuilderScreen.kt`,
`test/.../core/mcp/McpStdioTransportSecurityTest.kt`,
`test/.../core/mcp/McpHttpTransportSecurityTest.kt`,
`test/.../core/mcp/McpPhase81AttackSuiteTest.kt`, `implementation_plan_phase8_1.md`.

**Modified**: `core/mcp/McpTransport.kt` (`McpTransportException` made `open`),
`core/security/policy/McpSecurityClassifier.kt` (name-primary, escalate-only classification),
`core/security/policy/SecurityDiagnostics.kt` (4 transport checks), `core/di/AppContainer.kt`
(real transport factory + `mcpWorkingDir()`), `feature/mcp/McpViewModels.kt`
(`McpServerDetailViewModel`, argv parsing, grouping helpers), `feature/skills/SkillsViewModel.kt`
(upsert/duplicate/run), `feature/agentprofiles/AgentProfilesViewModel.kt` (upsert),
`navigation/DevStationNavGraph.kt` (3 destinations + flows).

**Not modified**: AgentRuntime, ToolRegistry, ToolExecutor, SecurityPolicyEngine, FilesystemSandbox,
approval system, Room schema (v6 unchanged — no migration needed), any Phase 1–7 source.

---

**Phase 8.1 complete. 469/469 tests pass. APK builds (20,030,074 bytes). Phase 9 not started.**
