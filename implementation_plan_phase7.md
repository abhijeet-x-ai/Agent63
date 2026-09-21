# DevStation — Phase 7 Implementation Plan
## Permissions + Sandbox + Security Hardening

---

### 1. Existing Security Architecture (Phases 1–6, inspected)

| Phase | Security-relevant component | What it provides today |
|---|---|---|
| 1 | `ProjectFileSystemManager` | project roots live under app-private storage |
| 1 | `KeystoreCredentialStore` (`SecureCredentialStore`) | AES-256-GCM secrets in EncryptedSharedPreferences + Android Keystore |
| 1 | `DevStationDatabase` | Room v4, additive migrations only (2→3, 3→4) |
| 2 | `TerminalManager` / `TerminalSession` | user terminal sessions, 5 000-line buffer, `SECRET_FILTER_REGEX` |
| 3 | `LinuxRuntimeManager` / `LinuxProcessLauncher` | Alpine rootfs, PRoot, bind mounts (`/workspace`, `/home/devstation`, `/dev`, `/proc`, `/sys`) |
| 4 | `EditorFileManager` | atomic saves, path/symlink checks, encoding + BOM preservation |
| 4 | `ProjectSearchEngine` | bounded project search |
| 5 | `AIProviderManager`, credential ids | API keys stay in the Keystore; Room stores references only |
| 6 | `PermissionManager`, `ApprovalBroker`, `ToolExecutor`, `PathSandbox`, `CommandClassifier`, `SecretRedactor`, `OutputLimiter`, `FileStateTracker`, `AgentProcessRegistry`, `agent_task_permissions` | per-tool permission, task grants, approvals, path sandbox, command classification, redaction, process ownership |

### 2. Phase 6 Permission Flow (the thing Phase 7 centralizes)

```
AI tool call → ToolRegistry → ToolArgumentValidator → ToolExecutor
            → CommandClassifier (terminal only)
            → PermissionManager.authorize (tool.definition.permission + classification + task grants)
            → ApprovalBroker.request (suspends) → user decision
            → tool.execute → sanitize (redact + truncate) → result
```

Gaps Phase 7 must close:

1. The decision is computed **inside the executor** from tool metadata; there is no single policy object
   and no audit trail of *denials*, *blocked paths* or *network* decisions.
2. Scopes are `PER_REQUEST` / `PER_TASK` only — no `SESSION`, `PROJECT`, `GLOBAL`.
3. No sensitive-file policy (a project `.env` is readable without extra scrutiny).
4. No network policy beyond the `NETWORK` command category; no destination reporting.
5. No project-level security settings, no security mode, no revocation UI.
6. No resource limits for agent processes (concurrency), no port/server tracking.
7. `LinuxProcessLauncher.launchProcess` inherits DevStation's **host process environment** and then
   overlays the Linux vars — agent commands must not inherit it (§14).
8. Approval is not re-checked immediately before execution (§49) and no TOCTOU re-validation exists (§50).

---

### 3. Target Architecture

```
                    USER
                     │  (only the user may change policy / approve)
                     ▼
                  AI AGENT ── AgentRuntime (bounded loop, taskId, sessionId)
                     │
                     ▼  SecurityRequest (tool, action, resource, risk, scope, project, task)
        ┌────────────────────────────────────────────────────────────┐
        │ SECURITY POLICY ENGINE  (the single decision point)        │
        │  1 global switch          → DENY when agent tools disabled │
        │  2 scope validation       → project/task/session present   │
        │  3 filesystem sandbox     → canonical path, private paths, │
        │                             cross-project, sensitive files │
        │  4 terminal policy        → CommandClassifier categories   │
        │  5 network policy         → NONE / LOCAL / INTERNET        │
        │  6 risk + permission      → ALLOW | ASK | ELEVATED | DENY  │
        │  7 resource limits        → processes, size, duration      │
        └───────────────┬────────────────────────────────────────────┘
                        │  SecurityDecision (+ reason, + sandbox token)
              ┌─────────┴──────────┐
              ▼                    ▼
        needs approval        allowed
              │                    │
     ApprovalBroker (Allow Once / Allow for Task / Allow for Session / Deny)
              └─────────┬──────────┘
                        ▼
              REVALIDATE (policy + fingerprint)   ← §49 / §50
                        ▼
                   TOOL EXECUTE
                        ▼
        SecurityAuditLogger → security_events (redacted, bounded, retention)
```

---

### 4. New Package `core/security/policy/`

| File | Contents |
|---|---|
| `SecurityModels.kt` | `ResourceType`, `SecurityAction`, `ImpactLevel`, `NetworkIntent`, `SecurityRequest`, `SecurityDecision`, `SandboxToken`, `FileFingerprint` |
| `SecurityPolicy.kt` | `AgentSecurityMode` (SAFE/BALANCED/CUSTOM), `PermissionCategory`, `CategoryPolicy`, `SecurityPolicy`, `SecurityPolicyProvider`, `StaticSecurityPolicyProvider` |
| `SensitiveFilePolicy.kt` | `.env*`, `*.pem`, `*.key`, `*.p12`, `*.pfx`, `id_rsa*`, `credentials.json`, `secrets.json`, `service-account*.json`, internal dirs; never filename-only |
| `FilesystemSandbox.kt` | canonical resolution (reuses `PathSandbox`), Android-private path rejection, cross-project rejection, sensitive detection, `FileFingerprint` capture + `revalidate` (TOCTOU) |
| `TerminalSecurityPolicy.kt` | extends `CommandClassifier` with `PACKAGE_REMOVE`, `SYSTEM`, `UNKNOWN`, `LOCAL_NETWORK`; unknown ⇒ approval; never a blacklist |
| `NetworkSecurityPolicy.kt` | intent + destination extraction (URL/host/port), query-parameter redaction, LOCAL vs INTERNET |
| `AgentResourceLimits.kt` | max concurrent processes, max process runtime, max output, max file size, max search results, documented as *enforced* vs *not enforceable* |
| `SecurityPolicyEngine.kt` | `authorize()` → `requestApproval()` → `revalidate()`; the only place that says yes |
| `SecurityAudit.kt` | `SecurityEventType`, `SecurityAuditEvent`, `SecurityAuditStore` + Room impl, `SecurityAuditLogger` (redact + bound) |
| `SecurityPolicyRepository.kt` | app policy, per-project settings, session/project grants, revocation, retention cleanup |
| `SecurityDiagnostics.kt` | real PASS/FAIL/WARNING checks (never a fabricated PASS) |

Reused, not duplicated: `PathSandbox`, `SecretRedactor`, `OutputLimiter`, `CommandClassifier`,
`ApprovalBroker`, `PermissionManager`, `AgentProcessRegistry`, `EditorFileManager`.

### 5. Permission Scopes & Expiration

| Scope | Lifetime | Can satisfy |
|---|---|---|
| `REQUEST` | one tool call | nothing (Allow Once re-asks next time) |
| `TASK` | until the task ends | `ASK` tool permission |
| `SESSION` | until the app session ends | `ASK` tool permission |
| `PROJECT` | explicit user configuration | never bypasses `ALWAYS_ASK`/`DENY` |
| `GLOBAL` | explicit user configuration | never bypasses `ALWAYS_ASK`/`DENY` |

`ALWAYS_ASK` (delete, destructive commands, sensitive deletes, credential access) is never satisfied
by any grant. Phase 7 adds no unrestricted permanent agent permission.

### 6. Default Permission Matrix (documented in the report)

Unchanged Phase 6 tool defaults in `BALANCED` mode; `SAFE` raises terminal/network/packages/sensitive
to `ALWAYS_ASK`; `CUSTOM` is user-controlled per category. Credential resources and Android-private
paths are `DENY` in every mode.

### 7. Filesystem Sandbox Rules

Reject `..` traversal, absolute Android paths (`/data/data`, `/data/user`, `/data/misc`, `/proc`,
`/sys`, `/dev`, `/vendor`, `/system`, `/apex`, `/data/app`), DevStation internals (`.devstation`,
`devstation_secure_prefs`), other projects, project-root delete/rename/replace, and any symlink whose
canonical target escapes the project root. Every write/delete/rename captures a fingerprint at
decision time and re-validates it immediately before the operation.

### 8. Terminal, Linux, Network Policies

* Terminal: classify every command; `UNKNOWN`/uncertain ⇒ approval; destructive ⇒ always ask;
  working directory must resolve inside the project; the whole command is validated as untrusted input.
* Linux: agent invocations run through `LinuxProcessLauncher` with an explicit, filtered host
  environment (never DevStation's environment) and only the existing `/workspace`,
  `/home/devstation`, `/dev`, `/proc`, `/sys` mounts — no `/`, `/data` or private paths.
* Network: `NO_NETWORK` / `LOCAL_NETWORK` / `INTERNET`; INTERNET and LOCAL both require approval by
  default; the approval card shows the destination with the query string redacted.

### 9. Audit Model

`security_events`: `id, timestamp, type, decision, risk, projectId, taskId, agentId, toolName, action,
resourceType, resourceSummary, sessionId`. Summaries are bounded (≤ 300 chars) and pass through
`SecretRedactor`; no full terminal output, no keys, no cookies. Retention is configurable
(7/30/90 days, default 30) with cleanup on startup and an explicit "Clear Security History".

### 10. UI

* Agent panel: security mode badge (`Security: SAFE|BALANCED|CUSTOM`), resource/risk/destination in
  the approval card, `Allow for Session` alongside Allow Once / Allow for Task / Deny.
* **Security Activity** screen: audit timeline with allowed/blocked badges.
* **Agent Permissions** screen: current project/task/session grants, category policies
  (Allow / Ask / Always ask / Deny), per-project security settings, revoke task/session/all, mode switch.
* **Security Diagnostics** screen: PASS/FAIL/WARNING report from real checks.
* Settings entries for all three screens; mode change and broadening confirmations explain impact.

### 11. Database Migration

v4 → v5, additive only: `security_settings` (single row), `project_security_settings`,
`security_events`, `permission_grants`. No Phase 1–6 data is touched; Phase 6
`agent_task_permissions` keeps working.

### 12. Test Strategy

* Unit: policy engine decisions per category/mode, scope validation, sandbox, sensitive files,
  terminal/network policy, expiration, revocation, audit logger, diagnostics, resource limits.
* Attack suite (§48): traversal, absolute/system/private paths, symlink escape, cross-project,
  root delete, credential/keystore access, `.env`, `/proc`, `/sys`, `/dev`, network bypass, package
  bypass, permission bypass, approval race, double execution, replay, expired/revoked grants, task/
  project/agent mismatch, process-ownership mismatch, timeout, oversized input/output, loop limits.
* Integration (§73): agent → read/search/modify/terminal/network/sensitive/escape/cancel/revoke.
* Race/TOCTOU (§49/§50): revoke between approval and execution must block; file changed between
  decision and write must block.

### 13. Attack Model

Assets: project files, other projects, credentials/Keystore, Android filesystem, host environment,
device resources. Adversaries: a malicious or manipulated model, prompt injection inside project
content, a user-approved-but-now-revoked permission, and an external file change racing the agent.
Mitigations: single decision point, deny-by-default unknown categories, capability-free `ToolContext`,
canonical-path sandbox, fingerprint revalidation, redacted bounded audit, and no policy mutation path
reachable from the model (policy lives in Room + user-only UI/VM APIs).

### 14. Migration from Phase 6 (§71)

`ToolExecutor` keeps its public API; the engine is injected (default = Phase 6-equivalent BALANCED
policy) so existing behavior and tests are preserved. Refinements: unknown commands become `UNKNOWN`
(stricter: always-ask) and package removals become `PACKAGE_REMOVE` (same always-ask permission).
`Allow Once` / `Allow for Task` / `Deny` semantics are unchanged.
