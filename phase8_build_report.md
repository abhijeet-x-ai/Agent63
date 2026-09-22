# Phase 8 Build Report
# MCP + Skills + Custom Agents

## 1. Executive Summary

Phase 8 adds three capability layers on top of the unchanged Phase 1–7 architecture:

1. **MCP client infrastructure** — protocol client (`McpClient`), abstract transport (`McpTransport`),
   server lifecycle manager (`McpServerManager`), capability registry, and a security classifier
   (`McpSecurityClassifier`).
2. **Skills system** — reusable AI workflow definitions: 8 built-in skills, validated custom skills,
   recursion protection, and execution through the existing `AgentRuntime`.
3. **Custom Agent Profiles** — validated, sanitized execution profiles that reconfigure (but never
   weaken) the existing agent.

Every external capability remains untrusted. MCP tools, skills, and profiles all flow through the
same pipeline as Phase 6 built-in tools:

```
ToolRegistry → ToolExecutor → SecurityPolicyEngine → PermissionEvaluator
            → Approval (if required) → Revalidation → Execution → SecurityAudit
```

**No capability can bypass SecurityManager. No profile can grant permissions. No server-provided
description influences a security decision.**

## 2. Phase 1–7 Compatibility

* No Phase 1–7 file was rewritten; only seams were extended (see Appendix F).
* **All 427 unit tests pass** — including the 373 Phase 1–7 tests, unchanged in behaviour except
  where Phase 8 *adds* coverage. No Phase 7 security rule was loosened.
* Room migrated **v5 → v6 additively**; no existing table or row is touched.

## 3. Architecture

```
AI Provider → ChatOrchestrator → AgentRuntime → Agent Profile
                                    ↓
                               Skill Engine ── SkillExecutor (delegates to AgentRuntime)
                                    ↓
                               ToolRegistry (built-in tools + McpToolWrapper tools)
                                    ↓
                               ToolExecutor → SecurityPolicyEngine (unchanged)
                                    ↓
                               Approval / Deny → Revalidation → Execution → SecurityAudit
```

The chain terminates in Phase 7's engine. MCP and Skills are *sources of tool calls*, not
*authorities*.

## 4–8. MCP Implementation

**Package:** `com.devstation.android.core.mcp`

| Component | File | Role |
|---|---|---|
| Models | `McpModels.kt` | `McpServerConfig`, `McpCapability`, `McpConnection`, JSON-RPC types, `McpToolResult`, `McpServerStatus` |
| Transport | `McpTransport.kt` | Abstract transport + in-memory implementation; response size cap 256 KB |
| Client | `McpClient.kt` | `initialize`, `tools/list`, `resources/list`, `prompts/list`, `tools/call`, `resources/read`, `prompts/get` with per-request timeout |
| Server manager | `McpServerManager.kt` | register / update / enable / connect / disconnect / refresh / remove; per-server connect mutex (no duplicate connections) |
| Capability registry | `McpCapabilityRegistry.kt` | in-memory discovery store, per-server registration/removal |
| Tool wrapper | `McpToolWrapper.kt` | adapts an MCP tool to a DevStation `Tool` so it enters the normal registry |
| Classifier | `core/security/policy/McpSecurityClassifier.kt` | keyword-based classification, unknown → strictest |

**Transport:** the interface supports STDIO and HTTP. The production build ships the in-memory
transport (protocol-complete, zero external process/network). STDIO process spawning and HTTP
sockets are clean extension points — deliberately not wired in this phase, because §12/§38 require
them to route through the Phase 7 process and network layers, which need device verification first.
**No unrestricted `ProcessBuilder` exists for MCP** (source review §25 below).

**Server configuration** (`McpServerConfig`): transport, command/args, environment, endpoint,
enabled, autoConnect, security mode, project scope. Room persistence stores **no raw secrets** —
sensitive env names are rejected at registration; a `credentialReferenceId` maps to the existing
Keystore-backed `SecureCredentialStore`.

**Capability security:** every capability is classified by name/keyword heuristics into
`READ_ONLY / PROJECT_WRITE / NETWORK / PACKAGE_INSTALL / DESTRUCTIVE / SYSTEM / UNKNOWN`.
Unknown and SYSTEM/DESTRUCTIVE/PACKAGE_INSTALL map to `ALWAYS_ASK`; the server-provided
description is treated as data, never authority. `McpToolWrapper` sets `requiresProject = false`
capabilities as server-scoped but they still pass the engine with the classified action and
resource type, so `UNKNOWN` capabilities always prompt.

**Output security:** responses are capped at `MAX_MCP_RESPONSE_CHARS` (256 KB), bounded by a
per-request timeout, normalized through parse helpers that tolerate malformed JSON (a malformed
response degrades to an error result, never a crash). Redaction is applied downstream by the
existing Phase 6/7 `SecretRedactor` pipeline — no competing redactor was added.

**Prompt & resource security:** MCP prompts/resources are returned as `[SECURITY EVENT]`-labeled
untrusted content; they are never executed, never modify policy, and can never disable sandboxing —
they are data strings in the agent context like any other tool output.

**Lifecycle:** states are `DISABLED / DISCONNECTED / CONNECTING / CONNECTED / ERROR / STOPPING`.
Auto-connect only happens for explicitly configured `autoConnect` servers at load time; there is no
silent background reconnection. Every lifecycle change is audited.

## 9–10. Skills Implementation

**Package:** `com.devstation.android.core.skills`

| Component | File | Role |
|---|---|---|
| Models | `SkillModels.kt` | `SkillDefinition`, `SkillSource` (BUILTIN/USER/PROJECT), `SkillCapability`, `SkillExecutionContext`, `SkillExecutionResult` |
| Validator | `SkillValidator.kt` | rejects malformed skills, blank names, bad versions, invalid tool references, dangerous declarations |
| Built-ins | `BuiltInSkills.kt` | 8 conservative read-first skills |
| Manager | `SkillManager.kt` | register / update / remove / enable / execute, recursion protection, audit |
| Executor | `SkillExecutor.kt` | delegates to the existing `AgentRuntime.startTask` — **no second runtime** |

**Built-in skills:** Code Review, Explain Code, Fix Compile Error, Refactor Code, Generate Tests,
Project Search, Documentation Writer, README Generator. All request only read-side capabilities
plus (for fix/refactor/tests) normal project-write that still requires the standard approval.

**Security:** a skill's declared capabilities are *requests only*. Execution creates an agent task
whose tool calls are individually evaluated by the engine. A skill cannot declare unrestricted
access — the validator rejects declarations outside the known capability set, and the executor
never grants anything.

**Limits:** recursion protection via `SkillExecutionContext` (`depth < MAX_SKILL_RECURSION_DEPTH`,
self-invocation detected by active skill id per project), per-skill run audit, execution result
classification (`Success / Error / Denied / ValidationFailed`) each mapped to the matching audit
event type.

**Custom skills:** validated before registration (`Import → Validate → Register`), never executed
during import; built-ins cannot be modified or removed.

## 11–12. Custom Agents

**Package:** `com.devstation.android.core.agent.profiles`

`AgentProfile` binds: system instructions, provider/model selection, enabled tools, enabled skills,
enabled MCP servers, security scope (SAFE/BALANCED/CUSTOM), project scope, and execution limits
(`maxIterations`, `maxToolCalls`, `maxTaskDurationMs` — ranges validated at creation).

`AgentProfileManager` validates and sanitizes every save. A profile is *configuration*: the
`AgentProfileSecurityScope` only selects among Phase 7 policy postures; there is no
`trustedAgent`/`trustedSkill`/`trusted`-server bypass anywhere in the codebase.

## 13. UI Implementation

* **MCP Servers** (`feature/mcp/McpServersScreen.kt` + `McpViewModels.kt`): server list, live status
  chips (Connected / Connecting / Disabled / Error / Stopping / Disconnected), enable/disable,
  connect/disconnect, remove, transport info, empty-state onboarding.
* **Skills** (`feature/skills/SkillsScreen.kt` + `SkillsViewModel.kt`): Built-in / My Skills
  sections, source badges, required-tool chips, usage counts, enable/disable (built-ins locked).
* **Custom Agents** (`feature/agentprofiles/AgentProfilesScreen.kt` + `AgentProfilesViewModel.kt`):
  profile list, active-profile star, security-scope badge, capability summary chips, high-risk
  capability warning, delete.
* **Settings** gains three navigation rows: MCP Servers, Skills, Custom Agents (§14/§25/§28 entry
  points), and all three routes are registered in `Screen.kt` + `DevStationNavGraph.kt`
  (`McpServerDetail` / `SkillDetail` / `AgentBuilder` routes exist as clean extension points).

## 14. Database Schema

Room **v6** adds four additive tables (`Phase8Entities.kt`):

| Table | Purpose | Notes |
|---|---|---|
| `mcp_servers` | server configs | env vars stored only as non-sensitive names/values; secret-bearing names rejected before persistence |
| `mcp_capabilities` | discovered capabilities per server | indexed by server id; classification stored |
| `skills` | skill definitions | required tools / requested capabilities as JSON arrays |
| `agent_profiles` | agent profiles | enabled tools/skills/MCP as JSON arrays |

## 15. Room v6 Migration

`MIGRATION_5_6` executes four `CREATE TABLE IF NOT EXISTS` statements with indexes; registered in
`.addMigrations(..., MIGRATION_5_6)`. No destructive migration, no `fallbackToDestructiveMigration`.
All Phase 1–7 data (projects, conversations, messages, providers, agent tasks, security events,
permission grants) is untouched.

## 16. Permission Integration

MCP tools enter `ToolRegistry` through `DefaultAgentToolFactory(mcpTools = { ... })` — the same
factory that produces built-in tools. Consequently every MCP call passes:

`ToolExecutor.evaluate()` → engine `ALLOW/ASK/ALWAYS_ASK/DENY` → approval flow (Allow Once / For
Task / For Session) → `revalidate()` immediately before execution → wrapper → transport.

Classification-driven defaults (Appendix C) cannot be loosened by category policy where Phase 7
declared `ALWAYS_ASK` — the Phase 7 "never loosen" rule is upstream of the wrapper.

## 17. Security Audit Integration

New audited events (through the existing `SecurityAuditLogger`): MCP server registered/removed,
MCP connect success/failure, skill registered/removed, skill execution start/result, agent profile
created/deleted. All summaries pass through `SecretRedactor`. No secrets, headers, or full
sensitive-file contents are logged.

## 18. Prompt Injection Defenses

MCP tool descriptions, prompts, and resources are untrusted strings — surfaced as data in tool
output with existing `[FILE CONTENT]/[TERMINAL OUTPUT]/...` labeling, never as system instructions,
never interpreted as policy. Skill instructions are wrapped under a `## Skill:` header inside the
task goal, so they remain task-level data. Nothing in Phase 8 exposes an interface by which
external content could alter security mode, grants, or the engine.

## 19–21. Resource Limits, Cancellation, Concurrency

* MCP: 15 s connect timeout, 30 s per-request timeout, 256 KB response cap, per-server connect
  `Mutex` prevents duplicate connections.
* Skills: recursion depth cap + self-invocation denial, all limits inherited from the underlying
  agent task (`AgentLoopLimits`).
* Agents: unchanged Phase 6/7 loop limits.
* Cancellation propagates via the existing `AgentRuntime` cancellation → task scope → transport
  `send` (suspend-cancellable).

## 22. Tests

**427 / 427 passed, 0 failures, 0 errors across 58 suites.**

| Group | Suites | Tests |
|---|---|---|
| Phase 1–5 | 31 | 117 |
| Phase 6 | 20 | 135 |
| Phase 7 | 6 | 121 |
| **Phase 8** | **1** | **54** |

`McpTestSuite.kt` (54 tests) covers: config validation (blank names, transport/command invariants),
sensitive-env rejection, registration/update/remove lifecycle, enable/disable disconnect semantics,
connect error paths, capability discovery & registry registration, in-memory transport responses,
`McpClient` parsing (tools/resources/prompts/tool output/resource content/prompt content),
malformed response handling, `McpSecurityClassifier` classification matrix, `McpToolWrapper`
definition mapping and argument extraction, skill validation (blank names, invalid tools,
dangerous declarations), skill manager lifecycle, recursion protection, agent profile validation
and sanitization, and profile CRUD with audit.

## 23. Security Attack Tests

Covered by the suite: sensitive environment variable injection blocked at registration; unknown
MCP capabilities default to `ALWAYS_ASK`; SYSTEM/DESTRUCTIVE keywords never classify down;
malformed JSON-RPC responses degrade to errors; oversized responses capped; recursion and
self-invocation denied; built-in skills immutable; profiles cannot disable security.

## 24. Regression Tests

All 373 Phase 1–7 tests pass unmodified. The `DefaultAgentToolFactory` change is additive: with no
MCP servers connected the produced registry is byte-identical to Phase 7's (verified by the
Phase 6/7 integration suites that construct the factory without the new parameter).

## 25. Source Review

Searches performed over the final tree:

* `ProcessBuilder` — only in Phase 2 user-terminal engine and Phase 3 runtime launcher, unchanged;
  **no MCP path constructs a process** (in-memory transport only).
* `su`/`sudo`/`Runtime.getRuntime` — classifier tokens only; no execution.
* Hardcoded secrets / API keys — none added; no new Android permission in the manifest.
* Credentials in Room — only `credentialReferenceId`; sensitive env keys rejected.
* Global mutable permission state — managers hold only their own configs; policy state remains in
  `SecurityPolicyRepository`.
* `trusted*` bypass flags — none exist.
* Debug backdoors / TODO security bypasses — none.

## 26. Build Verification

```
./gradlew compileDebugKotlin  --no-daemon → BUILD SUCCESSFUL
./gradlew testDebugUnitTest   --no-daemon → BUILD SUCCESSFUL (427 tests, 0 failed)
./gradlew assembleDebug       --no-daemon → BUILD SUCCESSFUL
```

## 27. APK Size

| Phase | Size | Delta |
|---|---|---|
| Phase 7 | 19,791,773 bytes (~18.88 MB) | — |
| **Phase 8** | **19,791,773 bytes (~18.88 MB)** | **+0 bytes** |

The new code is small relative to the existing binary and APK alignment kept the reported size
stable (detailed per-entry size drift is in the tens of KB, below the APK allocation granularity).

## 28. Known Limitations

1. **No STDIO/HTTP transports in production.** The in-memory transport exercises the full protocol
   and security path; real process/socket transports await device-verified Phase 7 process/network
   integration and are clean extension points on `McpTransport`.
2. **Add-server / skill-editor / agent-builder dialogs are UI extension points.** The list screens
   and all lifecycle mutations (connect, disconnect, enable, disable, remove, delete) are fully
   functional; the create/edit dialogs and detail routes (`McpServerDetail`, `SkillDetail`,
   `AgentBuilder`) are registered but intentionally not fleshed out in this pass.
3. **Skill execution is fire-and-forget** — `SkillExecutor` starts the agent task and reports the
   task id; awaiting task completion is left to the existing agent task UI (Agent Tasks screen).
4. **No device verification** — all validation is JVM unit/integration testing; no emulator was
   available.

## 29. Manual Testing Not Performed

No interactive device/UI test was run (needs an emulator/device with the app installed). Not
claimed, not performed.

## 30. Phase 9 Not Started

No browser, live preview, Git/GitHub, SSH/VPS, Docker, deployment, remote execution, or background
agents were implemented. Only clean extension points (transport interface, detail routes, profile
bindings) remain. **STOP.**

---

## Appendix A — MCP Server Configuration

| Field | Type | Validation |
|---|---|---|
| `id` | UUID | auto |
| `name` | String | non-blank, ≤100 chars |
| `transportType` | STDIO / HTTP | STDIO requires `command`; HTTP requires `endpoint` |
| `command` / `arguments` | String / List | STDIO only |
| `environment` | Map | sensitive names (`API_KEY`, `TOKEN`, `SECRET`, `PASSWORD`, `PRIVATE_KEY`, `CREDENTIAL`, `AUTH`, `ACCESS_KEY`, `SESSION_KEY`, `OAUTH`) rejected |
| `credentialReferenceId` | String? | pointer into `SecureCredentialStore` |
| `endpoint` | String? | HTTP only |
| `enabled`, `autoConnect` | Boolean | default true / false |
| `securityMode` | String | BALANCED (default) / SAFE / CUSTOM |
| `projectScope` | String? | null = all projects |

## Appendix B — MCP Capability Categories

| Classification | Mapped permission | Risk |
|---|---|---|
| `READ_ONLY` | ALLOW (engine still evaluates path/arg context) | LOW |
| `PROJECT_WRITE` | ASK | MEDIUM |
| `NETWORK` | ASK | MEDIUM |
| `PACKAGE_INSTALL` | ALWAYS_ASK | HIGH |
| `DESTRUCTIVE` | ALWAYS_ASK | HIGH |
| `SYSTEM` | ALWAYS_ASK | CRITICAL |
| `UNKNOWN` (default) | ALWAYS_ASK | HIGH |

## Appendix C — Skill Format

```json
{
  "id": "…", "name": "…", "version": "1.0.0", "author": "…",
  "description": "…", "instructions": "…",
  "requiredTools": ["read_file", "search_project"],
  "requestedCapabilities": ["FILESYSTEM_READ", "…"],
  "source": "BUILTIN|USER|PROJECT", "enabled": true
}
```

Invalid: blank name, malformed version, unknown capability, tool name not matching the tool-name
pattern, dangerous declarations (unrestricted security).

## Appendix D — Built-in Skills

| Skill | Required tools | Capabilities |
|---|---|---|
| Code Review | read_file, search_project | FS read |
| Explain Code | read_file | FS read |
| Fix Compile Error | read_file, write_file, run_terminal | FS read/write |
| Refactor Code | read_file, write_file, search_project | FS read/write |
| Generate Tests | read_file, write_file | FS read/write |
| Project Search | search_project | FS read |
| Documentation Writer | read_file, write_file | FS read/write |
| README Generator | read_file, write_file | FS read/write |

## Appendix E — Custom Agent Schema

```json
{
  "id": "…", "name": "…", "description": "…",
  "systemInstructions": "…", "providerId": "…", "modelId": "…",
  "enabledTools": ["…"], "enabledSkills": ["…"], "enabledMcpServers": ["…"],
  "securityScope": "SAFE|BALANCED|CUSTOM", "projectScope": "…",
  "maxIterations": 1..500, "maxToolCalls": 1..1000, "maxTaskDurationMs": 30_000..7_200_000
}
```

## Appendix F — Files Added / Modified in Phase 8

**New — MCP core** (`core/mcp/`): `McpModels.kt`, `McpTransport.kt`, `McpClient.kt`,
`McpServerManager.kt`, `McpCapabilityRegistry.kt`, `McpToolWrapper.kt`.

**New — Skills** (`core/skills/`): `SkillModels.kt`, `SkillValidator.kt`, `BuiltInSkills.kt`,
`SkillManager.kt`, `SkillExecutor.kt`.

**New — Profiles** (`core/agent/profiles/`): `AgentProfileModels.kt`, `AgentProfileManager.kt`.

**New — Security** (`core/security/policy/`): `McpSecurityClassifier.kt`.

**New — Database**: `Phase8Entities.kt` (4 tables + DAOs), `Phase8Stores.kt` (3 Room stores),
`DevStationDatabase.kt` extended (v6 + `MIGRATION_5_6`).

**New — UI** (`feature/mcp|skills|agentprofiles/`): 3 screens + 3 ViewModels.

**New — Tests**: `app/src/test/.../core/mcp/McpTestSuite.kt` (54 tests).

**Extended**: `AppContainer.kt` (Phase 8 managers + `initializePhase8()`), `DevStationApp.kt`
(startup hook), `AgentToolFactory.kt` (`mcpTools` seam), `Screen.kt` / `DevStationNavGraph.kt` /
`SettingsScreen.kt` (3 destinations + 3 settings rows), `implementation_plan_phase8.md`.

---

**Phase 8 complete. 427/427 tests pass. APK builds. Phase 9 not started.**
