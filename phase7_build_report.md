# DEVSTATION — PHASE 7: PERMISSIONS + SANDBOX + SECURITY HARDENING
## COMPREHENSIVE IMPLEMENTATION & VERIFICATION REPORT

**Date**: September 21, 2026  
**Project**: DevStation (Android Mobile AI Workstation)  
**Phase**: Phase 7 — Permissions + Sandbox + Security Hardening  
**Build Status**: **SUCCESS** (`compileDebugKotlin`, `testDebugUnitTest` 373/373 passing, `assembleDebug` APK produced)

---

## 1. Executive Summary

Phase 6 gave the agent *a* permission check. Phase 7 gives DevStation **one security authority**.

There is now exactly one place where an agent action can be authorized, and nothing in the tool layer
can execute without passing through it:

```
AI Agent → ToolExecutor
              │  SecurityRequest (tool, action, resource, risk, project, task, session)
              ▼
     SecurityPolicyEngine  ── the single decision point ──
       1 global safety switch        → DENY when agent tools are disabled
       2 scope validation           → project + task identity required
       3 project settings           → a disabled category is a hard DENY
       4 credential isolation       → DENY in every mode, always
       5 filesystem sandbox         → canonical path, Android-private rejection,
                                      cross-project rejection, sensitive-file detection
       6 terminal policy            → category + argument inspection + private-path block
       7 network policy             → INTERNET / LOCAL_NETWORK / NONE + redacted destination
       8 risk + category policy     → ALLOW | ASK | ELEVATED(always-ask) | DENY
              │
              ├─ ASK / ELEVATED → ApprovalBroker ("Allow once / for this task / for this session / Deny")
              ▼
     revalidate()  ← §49/§50: authorization **and** resource re-checked immediately before execution
              ▼
         TOOL EXECUTION
              ▼
     SecurityAuditLogger → security_events (redacted, bounded, retention)
```

New in this phase: a centralized `SecurityPolicyEngine`, a `FilesystemSandbox` with TOCTOU
fingerprints, a `SensitiveFilePolicy`, `TerminalSecurityPolicy` (with package-removal, system and
unknown categories), `NetworkSecurityPolicy` (with destination reporting), scoped grants
(REQUEST/TASK/SESSION/PROJECT), revocation, a redacted audit log with retention, security modes
(SAFE/BALANCED/CUSTOM), per-project settings **that can only tighten**, an 11-check security
diagnostics report, three new screens (Agent Permissions, Security Activity, Security Diagnostics),
and the §48 attack suite.

Phases 1–6 were **extended, never rewritten**: `PathSandbox`, `SecretRedactor`, `OutputLimiter`,
`CommandClassifier`, `ApprovalBroker`, `PermissionManager`, `AgentProcessRegistry`,
`EditorFileManager`, the AI provider system and Room all keep working as before (all `252` Phase 1–6
tests still pass, with 2 Phase 6 expectations *intentionally* tightened — see §24).

**Seven real defects were found and fixed because of this phase's tests** (§24). The most serious:

1. `NetworkSecurityPolicy` **crashed** on ordinary filenames (`No group 2`): every `cat README.md`
   style command would have thrown inside `assess()`. Any token shaped like `name.ext` triggered it.
2. The bare-hostname scan classified **`config.txt` as an internet host**, so a plain
   `cat config.txt` demanded a network approval.
3. `CategoryPolicy.ALLOW` on *files* **loosened `delete_file`** (declared `ALWAYS_ASK`) to ALLOW —
   a category setting could silently disable mandatory approval. Fixed: declared `ALWAYS_ASK`/`DENY`
   tools can be tightened but never loosened.
4. The network category policy was keyed off the *tool's* resource type, so "network access =
   always ask" had **no effect** on terminal commands. Fixed: a command that reaches the network is
   governed by the network policy.
5. `revalidate()` did not re-check **grants**, so a permission revoked between the approval and the
   execution did not cancel the call (§49). Fixed, with an explicit "decided now" exemption so
   Allow once / Allow for task keep working.
6. The Linux guest process **inherited DevStation's own environment** (where API keys live). Now the
   guest environment is cleared and rebuilt from a fixed safe set (§14/§66).
7. `ToolArgumentValidator` accepted a JSON **number where a string was declared**, so a malformed
   model call could still reach a tool body.

---

## 2. Security Architecture

| Layer | Component | Responsibility |
|---|---|---|
| Vocabulary | `core/security/policy/SecurityModels.kt` | `ResourceType`, `SecurityAction`, `ImpactLevel`, `NetworkIntent`, `SecurityRequest`, `SecurityDecision`, `SandboxToken`, `FileFingerprint` |
| Policy (data) | `SecurityPolicy.kt` | `AgentSecurityMode`, `PermissionCategory`, `CategoryPolicy`, `SecurityPolicy`, `ProjectSecuritySettings`, `AgentResourceLimits` |
| Decision | `SecurityPolicyEngine.kt` | `evaluate()` / `authorize()` / `revalidate()` — the only code that says yes |
| Filesystem | `FilesystemSandbox.kt`, `SensitiveFilePolicy.kt` | canonical containment, private-path rejection, sensitive detection, fingerprints |
| Terminal | `TerminalSecurityPolicy.kt` | category + argument + network assessment of a command string |
| Network | `NetworkSecurityPolicy.kt` | destination/host/port, local vs internet, redaction |
| Audit | `SecurityAudit.kt`, `SecurityRepository.kt` | redacted, bounded, retention-limited event log |
| User surface | `SecurityManager.kt` | policy, grants, revocation, retention, diagnostics — **user-only** |
| Verification | `SecurityDiagnostics.kt` | 11 real PASS/FAIL/WARNING checks |
| Persistence | `core/database/SecurityEntities.kt` | 4 additive tables (Room v5) |

Reused (not duplicated): `PathSandbox`, `SecretRedactor`, `OutputLimiter`, `CommandClassifier`,
`ApprovalBroker`, `PermissionManager`, `AgentProcessRegistry`, `EditorFileManager`,
`ProjectSearchEngine`, `LinuxProcessLauncher`, `SecureCredentialStore`.

`ToolExecutor` keeps its Phase 6 shape; the engine is injected (default = Phase 6-equivalent
BALANCED policy), so every existing tool, test and call site is unaffected.

---

## 3. Permission Architecture

```
SecurityPolicy            user intent (mode + 6 category policies + retention)
        +
ProjectSecuritySettings   per-project hard denials (can only tighten)
        +
Tool metadata             riskLevel / permission / resourceType / action /
                          networkImpact / filesystemImpact / destructive / sensitive (§44)
        +
Command classification    category + risk for terminal commands (§15)
        +
Grant store               REQUEST / TASK / SESSION / PROJECT grants (§4)
        ▼
SecurityPolicyEngine
```

* **Scopes**: `PER_REQUEST` (one call), `PER_TASK` (until the task ends), `SESSION` (until the app
  session ends), `PROJECT`/`GLOBAL` (explicit user configuration only).
* **Narrower is better**: there is no "allow forever" button anywhere. `ALLOW_FOREVER` does not exist.
* **`ALWAYS_ASK` is never satisfiable by a grant.** It is its own outcome (`ELEVATED`).
* **`DENY` cannot be overridden by any approval.** Credentials, private paths and cross-project
  paths are always `DENY`.
* **Project scope** (§5/§9): every request carries `projectId` + `projectRoot`; a missing or blank
  identity is a flat DENY, so the agent can never operate without a project.

---

## 4. Security Policy Engine

`SecurityPolicyEngine` exposes three functions and no mutation API:

| Function | Purpose |
|---|---|
| `evaluate(request, agentToolsEnabled)` | Pure policy evaluation → `ALLOW` / `ASK` / `ELEVATED` / `DENY` |
| `authorize(request, agentToolsEnabled)` | `evaluate` + the grant store (may turn `ASK` into `ALLOW`) |
| `revalidate(request, previous, agentToolsEnabled, approvedForThisRequest)` | §49/§50 re-check immediately before execution |

Evaluation order (each step can end the decision):

1. global agent-tools switch → `DENY`
2. project + task identity present → `DENY` if missing
3. per-project settings → `DENY` for a disabled category
4. `ResourceType.CREDENTIAL` → `DENY` (before any path work)
5. filesystem sandbox for path-bearing resources → `DENY` on any rejection; sets `SENSITIVE_FILE`
   and captures a `SandboxToken`
6. terminal assessment → `DENY` on a structural block (private path, unverifiable substitution in a
   shell-fallback context); records network intent, destination, port and sensitive arguments
7. network category policy `DENY` → `DENY` with the destination reported
8. required permission = tool declaration **and** command classification **and** network intent
   **and** sensitivity, then adjusted by the category policy (tighten-only for protected actions)
9. outcome mapped to `ALLOW` / `ASK` / `ELEVATED`, with a user-facing `reason` and `explanation`

`revalidate()` re-runs the **full authorization** (policy *and* grants) so a mid-task revocation,
a tightened mode or the global switch flipping off all take effect before the action rather than
after it. A user decision taken for this exact invocation ("Allow once / for this task / for this
session") is exempt from the grant lookup — but a hard `DENY` still cancels it.

---

## 5. Filesystem Sandbox

`FilesystemSandbox` is the only path validator for agent file operations. It wraps `PathSandbox`
(the Phase 6 rule set) and adds:

| Protection | Behaviour |
|---|---|
| Canonical containment | the canonical target must be inside **this** project root |
| Traversal | `../`, `../../`, `src/../../x`, `./../` → rejected |
| Absolute paths | anything absolute outside the project → rejected |
| Android private/system paths | `/data`, `/data/user`, `/data/misc`, `/proc`, `/sys`, `/dev`, `/vendor`, `/system`, `/apex`, `/etc`, `/root`, `/sdcard`, `/storage` → rejected with a specific reason |
| Cross-project | a sibling DevStation project is rejected even though it is inside app storage |
| Project root | delete / rename / replace of the root itself → rejected |
| DevStation internals | `.devstation/**` → rejected |
| Symlinks | the link is canonicalized; an escape (including a link to `/data/...`) → rejected |
| Sensitive files | flagged, which escalates the required permission and marks the resource |
| TOCTOU | a fingerprint (existence + size + mtime + SHA-256 for ≤4 MB) is captured at decision time and re-checked immediately before the write/delete/rename |

**Fingerprint strength matches the operation**: `CREATE` must still be free, `WRITE`/`DELETE`/
`RENAME` must match the full fingerprint, and read-only operations only require the existence to be
unchanged (so a concurrent edit cannot fail a read, but a *disappearing* file does).

---

## 6. Sensitive File Policy

`SensitiveFilePolicy` is deliberately over-inclusive (a false positive costs one approval):

* Names: `.env`, `.env.*`, `credentials.json`, `secrets.json`, `secrets.yaml`, `service-account*`,
  `id_rsa*`, `id_ed25519*`, `id_dsa*`, `.npmrc`, `.netrc`, `.pgpass`, `.git-credentials`,
  `keystore`, `keystore.jks`, `truststore.jks`, `known_hosts`, `devstation_secure_prefs`
* Extensions: `.pem`, `.key`, `.p12`, `.pfx`, `.jks`, `.keystore`, `.jceks`, `.pkcs12`, `.asc`, `.gpg`
* Directories: `.devstation`, `.ssh`, `.gnupg`, `.aws`, `.kube`

Default policy: **read → ASK, write → ASK, delete → ALWAYS_ASK**; the content is never persisted into
agent history or audit events (§52). Detection is never filename-only: the policy also matches by
directory and by command argument (`cat .env`, `cp .env .env.bak`), and the engine reports the
resource as `SENSITIVE_FILE` so the approval card can say why.

Credential-store internals and Android Keystore data are `ResourceType.CREDENTIAL` → **DENY** in
every mode. There is no credential tool in this phase, and the agent layer has no reference to
`SecureCredentialStore` at all (verified in the source review, §22).

---

## 7. Terminal Policy

`CommandCategory`: `READ_ONLY`, `MODIFY_PROJECT`, `LOCAL_NETWORK`, `INSTALL_PACKAGE`,
`PACKAGE_REMOVE`, `NETWORK`, `SYSTEM`, `UNKNOWN`, `DESTRUCTIVE`.

There is **no keyword blacklist**. Every command is tokenized (never executed), each segment is
classified, the most dangerous segment wins, and the arguments are inspected separately:

* shell substitution (`$(`, backtick, `${`) → `UNKNOWN` (always ask) because it cannot be verified
* output redirection (`>`, `>>`, `tee`) → never `READ_ONLY`
* a path argument naming an Android-private location → structural **DENY**
* `/proc/self/environ`-style reads → structural **DENY**
* a sensitive filename argument → escalates to at least ASK and is reported as sensitive
* in the Android-shell fallback, **every** path argument (relative or absolute) must resolve inside
  the project — `cat ../../shared_prefs/x.xml` and `cp src/App.kt /data/local/tmp/x` are blocked
* a command that starts a listener (`npm run dev`, `vite`, `python3 -m http.server`) becomes
  `LOCAL_NETWORK`, not a harmless read
* an unrecognised command is `UNKNOWN` → **always ask**, never "assume safe"

`git status`, `git log`, `ls`, `cat`, `grep`, `node --version`… remain `READ_ONLY` and may run
without a prompt; arguments cannot turn them into a write.

---

## 8. Linux Policy

* Agent commands run through `LinuxProcessLauncher` in the Phase 3 Alpine/PRoot runtime with the
  existing controlled mount set: **rootfs (`-r`)**, `/workspace` (the project), `/home/devstation`,
  and the `/dev`, `/proc`, `/sys` pseudo-filesystems. **No `/`, no `/data`, no app-private paths,
  and no other project directory is mounted** (§67).
* **Fixed in this phase**: the guest environment is now cleared and rebuilt from
  `LinuxEnvironment.buildEnvironment()` (HOME, PATH, TERM, LANG, USER, SHELL, PWD) plus explicitly
  passed variables. The guest no longer inherits DevStation's process environment — which is exactly
  where API keys live (§14/§66). `LD_PRELOAD` and `ANDROID_ROOT` remain filtered out.
* PRoot's `-0` is *guest* root only. DevStation never runs `su`/`sudo`/root Android APIs, and no new
  Android permission was requested (§63/§64).
* When Linux is unavailable the tool returns a structured error; it never silently switches to a
  different environment.

---

## 9. Network Policy

`NetworkSecurityPolicy` classifies intent **and** destination:

| Intent | Examples | Default |
|---|---|---|
| `NONE` | `ls -la`, `git status`, `cat src/App.kt` | no network policy applied |
| `LOCAL_NETWORK` | `curl http://127.0.0.1:8080/x`, `http://localhost:3000`, `http://192.168.*`, `http://10.*`, `[::1]`, `0.0.0.0`, local dev servers | ASK (never "harmless") |
| `INTERNET` | explicit URLs, bare hosts on a network command, `npm install`, `pip install`, `git clone` | ASK |

* Localhost is **its own class** and is never treated as safe — a local process can still bind ports,
  consume resources and read the project (§23).
* A bare hostname is only treated as a destination when the command is actually a network command;
  a filename that merely *looks* like a hostname (`config.txt`) is not (§21, and the bug fixed in §1).
* Approval cards and audit summaries show the destination with **userinfo and the query string
  stripped** (`https://user:pw@example.com/data?token=SECRET` → `https://example.com/data`), so no
  token leaks into the UI or the log (§22/§35).
* Ports are recorded where discoverable (`--port 8000`, `PORT=3000`, `host:port`) and
  `AgentProcessRegistry` tracks ports per agent task (§24).
* `NetworkSecurityPolicy.DENY` blocks the command outright; it cannot be granted.

---

## 10. Process Ownership

`AgentProcessRegistry` remains the single owner of agent-spawned processes, keyed by **agent task id**:

* `register(taskId, process)` / `unregister` / `ownedForTask` / `totalOwned` / `portsFor`
* `terminate(taskId)` destroys only that task's processes; `terminateAll()` only walks agent keys.
* The user's interactive terminal lives in a completely different subsystem (`future/terminal`,
  `TerminalManager`) and is **never** registered here, so **STOP AGENT / STOP ALL AGENT ACTIONS
  cannot terminate a user terminal** (§26/§56). A test asserts that terminating one task leaves
  another task's process alive.
* Every command carries a deadline and its owning task id; cancellation propagates
  UI → `AgentRuntime` → `ToolExecutor` → `ToolContext.isCancelled()` → the runner → `terminate`.
* Timeouts are recorded as `TIMEOUT` in the action history, never left running.

---

## 11. Resource Limits

`AgentResourceLimits` — every entry is actually enforced somewhere:

| Limit | Default | Enforced by |
|---|---|---|
| `maxConcurrentProcesses` | 3 | agent command layer |
| `maxProcessRuntimeMs` | 900 000 (15 min) | `BaseProcessCommandRunner` deadline, real `waitFor(ms)` |
| `maxCommandOutputChars` | 24 000 | `OutputLimiter` + `ToolExecutor.sanitize` |
| `maxFileSizeBytes` | 8 MB | `read_file` |
| `maxSearchResults` | 100 | `search_project` |
| `maxToolCalls` | 50 | `AgentRuntime` loop |
| `maxTaskDurationMs` | 600 000 (10 min) | `AgentRuntime` loop |
| `maxIterations` | 25 | `AgentRuntime` loop |
| Memory | **not enforced** | Android gives an unprivileged app no cgroup control — documented, not pretended |

When a limit is hit the task stops with an explicit message; it never continues silently.

---

## 12. Approval Scopes

| Button | Scope | Lifetime | Satisfies |
|---|---|---|---|
| **Allow Once** | `PER_REQUEST` | this one call | `ASK` and `ELEVATED` (once) |
| **For Task** | `PER_TASK` | until the task ends | `ASK` only |
| **For Session** | `SESSION` | until the app session ends | `ASK` only |
| **Deny** | — | — | ends the call |

`ELEVATED` (always-ask) actions are shown with a note that they cannot be granted for the whole
task, and the **For Task / For Session buttons are disabled** in that case. There is no
"Always allow forever".

---

## 13. Permission Expiration

* `REQUEST` — consumed by the single invocation; never stored (a test asserts a later identical call
  asks again).
* `TASK` — removed the moment the task completes, fails, is cancelled, or the app restarts.
* `SESSION` — bound to a per-process `sessionId`; a new app process deletes every persisted session
  grant (`revokeAllSessions()`), because a new process *is* a new session.
* `PROJECT`/`GLOBAL` — only ever written by explicit user configuration in the UI, and they still
  cannot satisfy `ALWAYS_ASK` or `DENY`.
* Expired rows (`expiresAt <= now`) are ignored by lookups and deleted by `pruneExpiredGrants()`.

---

## 14. Revocation

`SecurityManager` (user-only) exposes: **Revoke task**, **Revoke session**, **Reset project**,
**Reset all agent permissions**, plus **RevokeTaskPermissions on emergency stop**.

Revocation is immediate: the in-memory grant map is cleared *and* the persisted row is deleted, and
because `revalidate()` re-runs the full authorization immediately before execution, a revocation that
lands between the approval and the action still blocks that action (asserted by tests).

---

## 15. Security Audit Log

`security_events` records: `timestamp, type, decision, risk, projectId, taskId, sessionId, agentId,
toolName, action, resourceType, summary`.

Event types: `PERMISSION_REQUESTED`, `PERMISSION_GRANTED`, `PERMISSION_DENIED`, `PERMISSION_REVOKED`,
`TOOL_EXECUTED`, `TOOL_BLOCKED`, `PATH_BLOCKED`, `NETWORK_BLOCKED`, `SECRET_ACCESS_BLOCKED`,
`PROCESS_STARTED`, `PROCESS_TERMINATED`, `PROCESS_TIMEOUT`, `RESOURCE_LIMIT_REACHED`,
`AGENT_CANCELLED`, `SECURITY_POLICY_CHANGED`.

Privacy: `SecurityAuditLogger` runs `SecretRedactor.redact()` over the summary and bounds it to 300
characters **before** it reaches the store, so no caller can accidentally persist an API key, a
Bearer token, a password assignment or a megabyte of terminal output. Full command output is never
stored. A diagnostics check writes a fake API key and asserts the stored summary does not contain it.

Retention is configurable (7 / 30 / 90 days, default 30), applied on startup and on change, with an
explicit, confirmed **Clear security history**.

---

## 16. Security UI

**Agent Permissions** (`agent_permissions`)
* Security mode selector (SAFE / BALANCED / CUSTOM) with an impact confirmation before applying
* Category policy per category: `Tool default / Allow / Ask / Always ask / Deny`
* Credential storage shown as **always denied** and not editable
* Project scope picker with five per-project switches that can only remove capability
* Current grants: project / task / session, with **Revoke task**, **Revoke session**,
  **Reset all agent permissions**
* Audit retention options, and a link to diagnostics

**Security Activity** (`security_activity`) — the audit timeline: time, decision badge
(`✓ Allowed`, `? Asked`, `✕ Denied`, `✕ Blocked`), event type, tool and a redacted summary, plus a
confirmed clear.

**Security Diagnostics** (`security_diagnostics`) — runs the checks and shows
`n passed • n failed • n not verified` with a per-check PASS/FAIL/WARNING list.

**Agent panel** — a `Security: SAFE|BALANCED|CUSTOM` badge is always visible in the agent header
(§42); the approval card shows the tool, the exact target, the risk, whether it is always-ask, the
network destination, and the four buttons. Settings links to all three screens.

---

## 17. Security Diagnostics

`SecurityDiagnostics.run()` performs **eleven real checks** — each one carries out the attack it
claims to defend against:

| Check | What it actually does |
|---|---|
| Project root containment | resolves `../outside.txt`, `../../outside.txt`, `sub/../../outside.txt` and requires all three to be rejected |
| Absolute path rejection | resolves `/etc/passwd`, `/data/data/.../secret`, `/system/build.prop` |
| Android private paths | asks `FilesystemSandbox` about 9 system/private locations |
| Symlink escape | creates a real symlink to an existing file outside the project and requires rejection (WARNING when the filesystem has no symlink support or no writable outside location) |
| Project root protection | attempts delete of `.`, `/`, `""` |
| Sensitive file detection | checks 10 sensitive names + 3 sensitive directories |
| Credential isolation | checks the prefs name, the JKS names and the protected path list |
| Terminal policy | classifies 4 destructive, 2 unknown and 3 read-only commands |
| Network policy | classifies 2 internet, 2 local and 2 silent commands and requires localhost ≠ harmless |
| Permission scopes | builds a grant-everything engine and asserts an ASK is satisfied while an ALWAYS_ASK is not |
| Resource limits | validates every configured bound is non-degenerate and reports memory as unenforced |
| Audit logging / redaction | writes an event, reads it back, then writes a fake API key and asserts it did not survive |
| Process ownership | terminates an unrelated task and asserts zero processes were affected |
| Policy engine | runs a credential request, a cross-project request, a private-path request, an always-ask delete and a disabled-switch request against the live engine |

**A check that cannot run reports WARNING, never PASS.** With no project selected, the filesystem and
engine checks say so explicitly.

---

## 18. Attack Test Results

All attacks **fail safely**. (`SecurityAttackSuiteTest`, `FilesystemSandboxTest`,
`AgentSecurityIntegrationTest`, `SecurityPolicyEngineTest`, `TerminalAndNetworkPolicyTest`.)

| Attack | Result |
|---|---|
| Path traversal | ❌ blocked — `../`, `../../`, `src/../../x` rejected |
| Absolute paths | ❌ blocked — `/etc/passwd`, `/system/build.prop` rejected |
| Symlink escape (file outside, existing target) | ❌ blocked for READ, WRITE and DELETE |
| Symlink escape (diagnostics probe) | ❌ blocked |
| Cross-project access (absolute path) | ❌ blocked, engine-level and through the agent loop |
| Cross-project access (valid grant) | ❌ blocked — the path check runs after the grant check |
| Project root deletion | ❌ blocked — `.`, `/`, `""` rejected; the project still exists afterwards |
| Credential access (`ResourceType.CREDENTIAL`) | ❌ denied with every grant present |
| Credential policy loosening via API | ❌ impossible — `withCategory(CREDENTIALS, …)` is a no-op |
| Keystore/prefs path | ❌ blocked as a private path and as a sensitive name |
| `.env` access | ⚠ approval required; denied path reads nothing and the secret never enters the model context |
| Android private paths (`/data/data`, `/data/user`, `/data/misc`) | ❌ blocked |
| `/proc`, `/sys`, `/dev`, `/vendor`, `/system`, `/apex` | ❌ blocked |
| Terminal argument naming a private path | ❌ structural DENY, before the command runs |
| `/proc/self/environ` through the terminal | ❌ structural DENY |
| Shell-fallback relative traversal | ❌ blocked |
| Network bypass (curl / npm install in SAFE mode) | ⚠ always-ask; `Deny` runs nothing |
| Network policy set to DENY | ❌ blocked outright |
| Package installation | ⚠ ASK; denied → zero commands executed |
| Package removal | ⚠ always-ask; denied → zero commands executed |
| Destructive command (`rm -rf`) | ⚠ always-ask; denied → zero commands executed; **Allow for task cannot cover it** — the second call asks again |
| Permission bypass via category ALLOW | ❌ fixed — `ALLOW` can no longer loosen `delete_file` |
| Approval race (grant revoked after approval) | ❌ blocked at `revalidate()` |
| Approval race (policy tightened after approval) | ❌ blocked at `revalidate()` |
| Approval race (agent tools switched off mid-task) | ❌ blocked at `revalidate()` |
| Double execution / replay of an always-ask call | ⚠ asks again every time (3 consecutive replays asserted) |
| Expired grant | ❌ ignored by lookups and deleted by pruning |
| Revoked grant | ❌ blocked |
| Task mismatch (grant for task B, call in task A) | ❌ not honoured → ASK |
| Project mismatch (grant for project B) | ❌ not honoured → ASK |
| Session mismatch (grant from session B) | ❌ not honoured → ASK |
| Process-ownership mismatch | ✅ terminating task A leaves task B's process alive; an unknown task terminates nothing |
| Command timeout | ✅ deadline enforced, process terminated |
| Oversized output (200 000 chars) | ✅ bounded to the configured limit before reaching the model |
| Oversized input (2 MB string, wrong type, unknown parameter) | ❌ rejected by `ToolArgumentValidator` before the tool body runs |
| Infinite loop | ✅ stopped by iteration/tool-call limits |

---

## 19. Race-Condition Tests

| Race | Test | Result |
|---|---|---|
| Approval granted → grant revoked → execution | `a grant revoked mid-task is not honoured at execution time` | blocked, reason "no longer valid" |
| Emergency stop between approval and execution | `emergency stop invalidates every grant before the next execution` | blocked |
| Policy tightened between decision and execution | `tightening the policy mid-task blocks a previously allowed action` | blocked |
| Agent tools disabled between decision and execution | `disabling agent tools mid-task blocks the pending call` | blocked |
| File edited between decision and write | `a file changed after the decision blocks a write` | blocked |
| File deleted between decision and delete | `a delete is blocked when the file was already removed` | blocked |
| Name taken between decision and create | `a create is blocked when something else took the name` | blocked |
| Content changed between decision and read | `a read is not failed by a concurrent content change` | allowed (reads cannot clobber) |
| File disappeared between decision and read | `a read is blocked when the file disappeared` | blocked |
| User "Allow once" then a later identical call | `replaying an identical always-ask call asks again` | asks again |
| "Allow for task" on an always-ask action | `a destructive command cannot be granted for the whole task` | still asks |

The mechanism is `SecurityPolicyEngine.revalidate()` — called by `ToolExecutor` **after** the
approval and **immediately before** `tool.execute()`. A decision is only ever valid for the instant
it was made.

---

## 20. TOCTOU Protection

Sequence for every write/delete/rename/create:

```
sandbox.resolve(projectRoot, rawPath, operation)   →  Allowed(File, SandboxToken(fingerprint))
        │
        │  (approval may take an arbitrary amount of time)
        ▼
sandbox.revalidate(token)  →  existence must match; for WRITE/DELETE/RENAME also
                              size + mtime + SHA-256 must match
        ▼
tool.execute()
```

* The fingerprint is captured at decision time, not at execution time.
* Existence is compared **symmetrically** (a file that appeared is as much a conflict as one that
  vanished).
* A `CREATE` whose name was taken is blocked, so nothing is silently overwritten.
* Failures produce a specific message ("changed size after the security check", "was created by
  something else while the agent was working") and leave the file untouched.
* `apply_patch` additionally verifies the content hash of the last read via `FileStateTracker`
  (Phase 6 §43/§44), so an AI patch is rejected if the file changed since it was read.

**Known limit**: the check is a compare-then-act, so a change landing in the microseconds between
`revalidate()` and the OS call is still theoretically possible. On Android/JVM there is no atomic
compare-and-swap for files without extra locking; the agent therefore writes atomically through
`EditorFileManager` and re-reads after a conflict. Documented rather than claimed as absolute.

---

## 21. Database Migration

Room **v4 → v5**, additive only (`MIGRATION_4_5`), no destructive fallback:

| Table | Purpose |
|---|---|
| `security_settings` | single row: serialized `SecurityPolicy` (no secrets) |
| `project_security_settings` | per-project switches, indexed by `projectId` |
| `security_events` | redacted audit events, indexed by `timestamp`, `projectId`, `taskId` |
| `permission_grants` | SESSION/PROJECT grants with `expiresAt`, indexed by scope ids |

Phase 6's `agent_task_permissions` table is untouched and keeps working. Phase 1–5 data is untouched.
`CREATE TABLE IF NOT EXISTS` plus `CREATE INDEX IF NOT EXISTS` is used so a partially-migrated device
cannot wedge. Only non-secret values are ever written.

---

## 22. Android Security Model

* **No new Android permission was requested.** The app continues to use only its own sandbox.
* No `READ/WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`, no accessibility service, no device
  administrator, no root (§63).
* Project files live in app-private storage; the agent can only reach paths proven to be inside the
  selected project.
* API keys stay in `EncryptedSharedPreferences` + the Android Keystore (`SecureCredentialStore`). The
  agent layer has **no reference** to it; the security system treats it as a protected resource and
  denies every credential request.
* Tool output is redacted (`SecretRedactor`) and bounded (`OutputLimiter`) before it can reach the
  model context.
* Tool results are labelled (`[FILE CONTENT]`, `[TERMINAL OUTPUT]`, `[SEARCH RESULT]`,
  `[EDITOR STATE]`) and the system prompt states that file contents, terminal output and logs are
  **untrusted data** that can never change the security policy (§54/§55/§73).
* The model has **no path to policy mutation**: policy, project settings and grants are reachable only
  from `SecurityManager` (ViewModels/UI). An agent cannot change the mode, grant itself a permission,
  disable the sandbox or disable auditing.

---

## 23. Rootless Architecture

* DevStation never invokes `su`, `sudo`, or root Android APIs. `su`/`sudo` appear only as *classifier
  tokens* that map to `CommandCategory.SYSTEM` → **always ask**; the app itself never runs them.
* PRoot's `-0` (fake root) is confined to the guest rootfs and is unrelated to Android root — it is
  the standard, rootless userspace technique used since Phase 3 and is documented as guest-only.
* The Android-shell fallback is opt-in (`agentAllowAndroidShell`) and, when used, clears the
  environment and confines every path argument to the project (§7).
* Verified by source review: `ProcessBuilder` exists only in the Phase 2 user terminal and the Phase 3
  runtime launcher — never reachable from a tool.

---

## 24. Tests

**373 / 373 tests pass across 57 suites, 0 failures, 0 errors.**

| Group | Suites | Tests |
|---|---|---|
| Phase 1–5 (regression-free) | 31 | 117 |
| Phase 6 (agent, tools, providers) | 20 | 135 |
| **Phase 7 (new)** | **6** | **121** |
| **Total** | **57** | **373** |

Phase 7 suites:

| Suite | Tests | Coverage |
|---|---|---|
| `SecurityPolicyEngineTest` | 27 | decisions per category and mode, identity, credential denial, scopes, always-ask, terminal/network policy, project settings, revalidation |
| `AgentSecurityIntegrationTest` | 25 | the **real** agent loop + **real** tool registry + **real** engine: reads, list/search, escapes, symlink, approvals before execution, deny, destructive/package/network approvals, redaction, output bounding, modes, project settings, process ownership, task isolation, loop limits |
| `FilesystemSandboxTest` | 20 | containment, traversal, absolute/private paths, root protection, internal dir, symlinks, sensitive detection, fingerprints and every revalidate branch |
| `TerminalAndNetworkPolicyTest` | 19 | categories, redirection, substitution, private paths, sensitive args, fallback-shell confinement, local network, destination redaction, ports |
| `SecurityAuditAndDiagnosticsTest` | 17 | redaction, bounding, retention, clear, Room round-trip, all diagnostics checks, mode changes, grant persistence across a restart, revocation, project settings, overview, retention |
| `SecurityAttackSuiteTest` | 13 | task/session/project mismatch, matching grant, expiry, revocation, emergency stop, replay, oversized/unknown/wrong-typed arguments, audit completeness |

**Defects found and fixed by these tests (this is the value of the suite):**

1. `NetworkSecurityPolicy` threw `IndexOutOfBoundsException: No group 2` for any `name.ext` token —
   i.e. `cat README.md` crashed the assessment. Fixed (the host is the match, group 1 is the port).
2. A bare filename that looks like a hostname (`config.txt`) was classified as `INTERNET`, causing a
   spurious approval for ordinary reads. Fixed: bare hosts are only considered for network commands.
3. `python3 -m http.server` was classified as `READ_ONLY`; a listening server is now `LOCAL_NETWORK`.
4. `CategoryPolicy.ALLOW` on files loosened `delete_file` (`ALWAYS_ASK`) to `ALLOW`. Fixed: a declared
   `ALWAYS_ASK`/`DENY` permission can be tightened but never loosened.
5. The network category policy had no effect on terminal commands (wrong category mapping). Fixed.
6. `revalidate()` did not re-check grants (§49). Fixed with an explicit "decided now" exemption.
7. The Linux guest inherited DevStation's environment (API keys). Fixed.
8. `ToolArgumentValidator` accepted a number where a string was declared. Fixed.

Two Phase 6 test expectations were **deliberately** tightened rather than kept, and the tests were
updated to document the new, stricter contract:

* an unrecognised command is `UNKNOWN` (always-ask) instead of `MODIFY_PROJECT` (ask);
* package removal is its own category `PACKAGE_REMOVE` (still always-ask).

Neither change loosens anything, and `Allow Once` / `Allow for Task` / `Deny` behave exactly as before.

---

## 25. Build Results

| Check | Result |
|---|---|
| `./gradlew compileDebugKotlin` | ✅ 0 errors (no new warnings from Phase 7 sources beyond the project's existing `Icons.Default.ArrowBack` deprecation convention) |
| `./gradlew testDebugUnitTest` | ✅ **373/373 passed, 0 failures, 0 errors** (57 suites) |
| `./gradlew assembleDebug` | ✅ APK produced |

Commands run (final, frozen state):

```
./gradlew compileDebugKotlin --no-daemon      → BUILD SUCCESSFUL
./gradlew testDebugUnitTest  --no-daemon      → BUILD SUCCESSFUL (373 tests, 0 failed)
./gradlew assembleDebug      --no-daemon      → BUILD SUCCESSFUL
```

---

## 26. APK Path

```
app/build/outputs/apk/debug/app-debug.apk
```

## 27. APK Size

| Phase | Size | Delta |
|---|---|---|
| Phase 5 | 18,725,726 bytes (~17.86 MB) | — |
| Phase 6 | 19,167,570 bytes (~18.28 MB) | +0.42 MB |
| **Phase 7** | **19,791,773 bytes (~18.88 MB)** | **+0.60 MB** |

The increase is the Phase 7 security core (11 new source files), the three security screens, and the
Room v5 tables. No new dependency was added in this phase.

---

## 28. Manual Test Status

**NOT PERFORMED — documented as a limitation, not claimed as verified.**

The §74 manual security test requires a real Android device or emulator (a project filesystem, the
Alpine/PRoot runtime, a configured AI provider, and interactive approval taps). No device was
available in this environment, so items 1–30 of §74 — including "create Project A and Project B, ask
the agent to read the other project, verify denial", "create a symlink escape, verify denial", "press
STOP ALL AGENT ACTIONS, verify the user's own terminal survives", and "restart the app, verify no
automatic resume" — were **not** executed by hand.

What *was* verified instead, automatically and against the real runtime code:

* every attack in §18 runs through the real `SecurityPolicyEngine`, the real `AgentRuntime` loop and
  the real tool registry (`AgentSecurityIntegrationTest`);
* process ownership isolation, grant expiry and revocation are asserted directly;
* Room v5 migration SQL is exercised by the compile-time schema validation of `@Entity`/`@Dao`
  (Room's generated code fails the build on a mismatch) — but the *runtime* migration path on a
  device with existing Phase 6 data has not been executed.

---

## 29. Known Limitations

1. **No device verification** (§28). Everything proven here is proven in JVM unit/integration tests
   against production classes.
2. **TOCTOU is compare-then-act**, not atomic (§20). A change landing in the window between
   `revalidate()` and the OS call is not detectable without file locking, which Android does not
   offer for this case.
3. **Per-process memory is not enforceable** by an unrooted app; `AgentResourceLimits` says so
   explicitly instead of pretending otherwise.
4. **Sensitive-file detection is heuristic.** It is intentionally over-inclusive and includes
   argument inspection, but a secret in a file with an innocuous name (e.g. `notes.txt`) is not
   detected. `SecretRedactor` is likewise best-effort: it recognises common key shapes and
   `key = value` assignments, not every possible secret.
5. **Command classification cannot be complete.** Anything unrecognised is `UNKNOWN` → always ask,
   which is safe but can be noisy; a project-specific command always needs a decision.
6. **Network destination detection is static.** A command that reaches the network without naming a
   destination (or through a script it invokes) is classified by name, not by observing traffic.
   There is no traffic interception (and none is possible without root).
7. **`PROJECT`/`GLOBAL` grants are only writable from the UI.** There is currently no UI to create a
   project-scoped "always allow" grant; the store and the enforcement path exist, the button does not.
   This is deliberate for Phase 7 (narrower scopes by default) and is the main Phase 8 UI hook.
8. **Only one security policy row exists** (app-wide). Per-project *settings* exist and can only
   tighten, but a fully per-project policy override is not implemented.
9. **Session scoping is per app process.** A configuration change that restarts the process ends the
   session and clears session grants — correct, but it means a long session across a process restart
   re-asks.
10. **The Android-shell fallback is still an option.** It is off by default in the agent path
    (`allowAndroidFallback`), bounded to the project, and environment-cleared, but it is a weaker
    environment than the Linux guest.

---

## 30. Phase 8 Recommendations

Phase 7 deliberately stopped at the security boundary. Natural next steps, in priority order:

1. **Device verification of §74** — run the 30-step manual security test on a real device/emulator,
   plus an instrumented migration test from a v4 database.
2. **Per-project policy overrides + a "grant for this project" UI**, using the existing
   `permission_grants` table and `SecurityPolicyProvider(projectId)` seam (the provider already takes
   a project id; only the UI and one accessor are missing).
3. **A security settings export/import** (redacted, no secrets) so a hardened policy can be moved
   between devices.
4. **Structured command metadata instead of string classification** — a small command registry that
   declares `category`, `writesFiles`, `networkIntent` and `deletesData` per known tool (npm, gradle,
   git, apk), with the classifier as the fallback for everything else.
5. **A network egress allow-list per project** (host + port), so a project can declare "only the npm
   registry" and everything else is blocked even with approval.
6. **Resource-limit telemetry** — surface near-limit and limit-reached events in the UI with the
   offending command, so a user can see *why* a task stopped.
7. **Security policy for future capabilities** — when MCP, browser, Git/GitHub or deployment tools
   arrive, they must arrive as *new `ResourceType`s and `PermissionCategory`s through this engine*,
   with their own default posture, never as new bypasses. The `ToolRegistry`
   (`AgentToolFactory`) is the single place to add them.

---

## Appendix A — Default Permission Matrix

`BALANCED` (the default mode). `SAFE` and `CUSTOM` are shown where they differ.

| Tool | Risk | Declared | Effective in BALANCED |
|---|---|---|---|
| `read_file` | LOW | ALLOW | **ALLOW** (sensitive path → ASK) |
| `list_directory` | LOW | ALLOW | **ALLOW** |
| `search_project` | LOW | ALLOW | **ALLOW** |
| `open_file` | LOW | ALLOW | **ALLOW** |
| `get_editor_state` | LOW | ALLOW | **ALLOW** |
| `get_current_file` | LOW | ALLOW | **ALLOW** |
| `write_file` | MEDIUM | ASK | **ASK** |
| `apply_patch` | MEDIUM | ASK | **ASK** |
| `create_file` | MEDIUM | ASK | **ASK** |
| `create_directory` | MEDIUM | ASK | **ASK** |
| `rename_file` | MEDIUM | ASK | **ASK** |
| `delete_file` | HIGH | ALWAYS_ASK (destructive) | **ALWAYS_ASK** |
| `run_terminal_command` | HIGH | classification-driven | see Appendix B |

| Resource / condition | BALANCED | SAFE | CUSTOM |
|---|---|---|---|
| Project file read | ALLOW | ALLOW | user |
| Project file write | ASK | ASK | user |
| Sensitive file read/write | ASK | **ALWAYS_ASK** | user (ASK default) |
| Sensitive file delete | ALWAYS_ASK | ALWAYS_ASK | ALWAYS_ASK |
| Terminal — read-only | ALLOW | ALLOW | user |
| Terminal — project modify | ASK | ASK | user |
| Terminal — install package | ASK | **ALWAYS_ASK** | user |
| Terminal — remove package | ALWAYS_ASK | ALWAYS_ASK | ALWAYS_ASK |
| Terminal — destructive / system / unknown | ALWAYS_ASK | ALWAYS_ASK | ALWAYS_ASK |
| Network (internet, incl. package managers) | ASK | **ALWAYS_ASK** | user |
| Network (local/private, incl. dev servers) | ASK | **ALWAYS_ASK** | user |
| **Credentials / Keystore / secure prefs** | **DENY** | **DENY** | **DENY** |
| **Android private/system paths** | **DENY** | **DENY** | **DENY** |
| **Cross-project paths** | **DENY** | **DENY** | **DENY** |
| **Project root delete/rename** | **DENY** | **DENY** | **DENY** |
| **Agent tools disabled (global switch)** | **DENY** | **DENY** | **DENY** |
| Project security setting off | **DENY** | **DENY** | **DENY** |

## Appendix B — Tools Requiring Approval

**Always ask, never grantable for a task/session** (`ALWAYS_ASK` → `ELEVATED`):
`delete_file`; any terminal command classified `DESTRUCTIVE`, `PACKAGE_REMOVE`, `SYSTEM` or
`UNKNOWN`; any command with unverifiable shell substitution; deleting a sensitive file; every network
command and every package installation while in **SAFE** mode.

**Ask, grantable for this task / this session** (`ASK`): `write_file`, `apply_patch`, `create_file`,
`create_directory`, `rename_file`; terminal commands classified `MODIFY_PROJECT`, `INSTALL_PACKAGE`,
`NETWORK`, `LOCAL_NETWORK`; reading or writing a sensitive file; any request whose category policy is
`ASK`/`ALWAYS_ASK`.

**Allowed without a prompt**: the six read-only tools, and terminal commands classified `READ_ONLY`.

## Appendix C — Always-Denied Resources

1. `ResourceType.CREDENTIAL` — the credential store, Android Keystore material, `devstation_secure_prefs`
2. Android private/system locations: `/data`, `/data/user`, `/data/misc`, `/proc`, `/sys`, `/dev`,
   `/vendor`, `/system`, `/apex`, `/etc`, `/root`, `/sdcard`, `/storage`
3. Any path outside the selected project — including paths inside **other DevStation projects**
4. The project root itself for write/delete/rename
5. DevStation's internal `.devstation/**` directory
6. Any symlink (or symlinked parent) whose canonical target escapes the project root
7. Any request whose category is disabled in that project's security settings
8. Every request while the agent-tools safety switch is off
9. Any policy loosening of the `CREDENTIALS` category (the API refuses it)

## Appendix D — Permission Scope Summary

| Scope | Created by | Lifetime | Stored | Can satisfy |
|---|---|---|---|---|
| `REQUEST` | "Allow once" | one tool call | never | any ask, once |
| `TASK` | "For task" | until the task ends | `agent_task_permissions` (Phase 6) + in-memory | `ASK` only |
| `SESSION` | "For session" | until the app session ends (i.e. process death) | `permission_grants` (scope=SESSION), deleted on next start | `ASK` only |
| `PROJECT` / `GLOBAL` | explicit user configuration | until reset | `permission_grants` (scope=PROJECT/GLOBAL) | `ASK` only |
| **any** | — | — | — | **never** `ALWAYS_ASK` or `DENY` |

## Appendix E — Files Added / Modified in Phase 7

**New — security core** (`app/src/main/java/com/devstation/android/core/security/policy/`):
`SecurityModels.kt`, `SecurityPolicy.kt`, `SensitiveFilePolicy.kt`, `FilesystemSandbox.kt`,
`TerminalSecurityPolicy.kt`, `NetworkSecurityPolicy.kt`, `SecurityAudit.kt`,
`SecurityRepository.kt`, `SecurityPolicyEngine.kt`, `SecurityManager.kt`, `SecurityDiagnostics.kt`

**New — persistence**: `core/database/SecurityEntities.kt` (4 tables + 4 DAOs)

**New — UI** (`feature/security/`): `AgentPermissionsScreen.kt`, `SecurityActivityScreen.kt`,
`SecurityDiagnosticsScreen.kt`

**New — tests** (`app/src/test/.../core/security/`): `SecurityPolicyEngineTest.kt`,
`FilesystemSandboxTest.kt`, `TerminalAndNetworkPolicyTest.kt`, `SecurityAuditAndDiagnosticsTest.kt`,
`SecurityAttackSuiteTest.kt`, `AgentSecurityIntegrationTest.kt`, `SecurityTestFixtures.kt`

**Extended (no competing architecture)**: `DevStationDatabase.kt` (v5 + `MIGRATION_4_5`),
`AppContainer.kt` (engine/audit/session/manager wiring), `DevStationApp.kt` (startup security init),
`PermissionManager.kt` (session/project grants, `ApprovalResolution`), `Approval.kt`
(`ALLOW_FOR_SESSION`, `elevated`, destination/impact), `ToolExecutor.kt` (engine + revalidation),
`AgentRuntime.kt` (engine/audit/session injection, audited lifecycle), `AgentTool.kt` (Phase 7 tool
metadata), `CommandClassifier.kt` (new categories), `ToolArgumentValidator.kt` (strict string type),
`FileTools.kt` / `SearchTool.kt` / `EditorTools.kt` / `TerminalTools.kt` (metadata + injectable
tracker), `LinuxProcessLauncher.kt` (environment clearing), `Screen.kt` / `DevStationNavGraph.kt` /
`SettingsScreen.kt` (3 screens + entries), `AgentViewModel.kt` / `AgentPanel.kt` (mode badge,
"for session"), `AiChatScreen.kt`, `implementation_plan_phase7.md`.

---

**STOP.** Phase 7 is implemented, tested and packaged. No MCP, Skills, browser, GitHub, SSH/VPS,
remote execution, Docker, deployment, or scheduled/background agents were implemented, and Phase 8
was not started.
