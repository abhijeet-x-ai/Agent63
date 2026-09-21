# DevStation — Phase 6 Implementation Plan
## AI Agent + Tool Execution System

**Scope**: Build the first controlled AI coding agent on top of the existing Phase 1–5
architecture. Phase 6 adds an agent runtime, a sandboxed tool layer, a real permission /
approval system, task persistence, and an agent UI. It does **not** add MCP, Skills,
Browser, GitHub, SSH/VPS, Docker, deployment, or autonomous background/scheduled agents.

---

## 1. Existing architecture this phase extends

| Phase | Component | Phase 6 usage |
|---|---|---|
| 1 | Room (`DevStationDatabase`) | new additive tables via migration `3 → 4` |
| 1 | `ProjectFileSystemManager` | default workspace root, project resolution |
| 1 | `SecureCredentialStore` (Keystore) | provider keys only — never exposed to tools |
| 1 | `ConversationRepository` | conversation history + final agent answer |
| 2 | `TerminalManager` / `TerminalSession` | **not reused** for agent commands (session isolation) |
| 3 | `LinuxRuntimeManager`, `LinuxProcessLauncher` | `run_terminal_command` executes inside Linux `/workspace` |
| 4 | `EditorFileManager` | atomic saves for every AI write (no second save mechanism) |
| 4 | `ProjectSearchEngine` | `search_project` (no second search engine) |
| 4 | `EditorViewModel` / `EditorScreen` | `open_file` navigates the real editor (no duplicate editor) |
| 5 | `AIProvider`, `AIProviderManager`, `AIRequest`, `AIResponseEvent` | native tool calling + agent loop model turns |

No Phase 1–5 file is rewritten. All Phase 5 changes are **additive** (new optional fields,
one new event type, one new enum value, new capability flags).

---

## 2. Agent architecture

```
                       USER
                        │  (goal + approvals)
                        ▼
                 AgentRuntime ──────────────► AgentEvent flow (StateFlow/SharedFlow)
                        │                              │
        ┌───────────────┼────────────────┐             ▼
        ▼               ▼                ▼        feature/agent UI
  AIProviderManager  ContextBuilder  ToolExecutor  (timeline, approvals, summary)
        │                                │
   OpenAI/Gemini/               ┌────────┴─────────┐
   Anthropic adapters           ▼                  ▼
                          PermissionManager   ToolRegistry
                                │                  │
                          ApprovalBroker    File/Search/Editor/Terminal tools
                                                   │
                                                   ▼
                                     PathSandbox → EditorFileManager / LinuxRuntime
```

Layers never invert: the UI only sees `AgentEvent`s; the runtime only sees the
`AIProviderManager` abstraction; tools only see a `ToolContext` (no Android `Context`,
no credentials, no raw process API).

---

## 3. Agent state machine

```
IDLE ──start──► PLANNING ──► WAITING_FOR_MODEL ──┬──► PLANNING (tool calls returned)
                    ▲                            ├──► WAITING_FOR_APPROVAL ──► EXECUTING_TOOL
                    │                            │                                  │
                    └────────────────────────────┘◄─────────────────────────────────┘
                                                 └──► COMPLETED
                                                      FAILED
                                                      CANCELLED
                                                      PAUSED
                                                      INTERRUPTED (restart recovery)
```

- Every transition is explicit and persisted for non-transient states.
- `INTERRUPTED` is set only by startup recovery; Phase 6 never auto-resumes a task.

---

## 4. Agent events (UI contract)

`AgentStarted`, `AgentThinking`, `AgentMessage`, `ToolCallRequested`,
`ToolApprovalRequired`, `ToolStarted`, `ToolOutput`, `ToolCompleted`, `ToolFailed`,
`ToolDenied`, `AgentWaiting`, `AgentLimitReached`, `AgentCompleted`, `AgentFailed`,
`AgentCancelled`.

The UI never parses provider JSON and never displays hidden chain-of-thought —
`AgentThinking` carries only a short status label, and `ThinkingDelta` content is not
persisted.

---

## 5. Tool system

```kotlin
interface Tool {
    val definition: ToolDefinition          // name, description, schema, risk, permission
    suspend fun execute(args: JsonObject, context: ToolContext): ToolResult
}
```

`ToolDefinition` = provider-neutral `AIToolSpec` (name, description, typed parameters) +
`ToolPriorityRisk` + default `ToolPermission`.

`ToolResult` is normalized to `Success | Error | Denied | Cancelled | Timeout`, each
carrying bounded, redacted output plus metadata — never secrets.

`ToolContext` carries only: `projectId`, `projectRoot`, `workingDirectory`, `agentId`,
`taskId`, `permissionScope`, `isCancelled()` and an `invokeTool`-free narrow API set.

### 5.1 Tool registry (Phase 6)

| Tool | Risk | Default permission |
|---|---|---|
| `read_file` | LOW | ALLOW |
| `list_directory` | LOW | ALLOW |
| `search_project` | LOW | ALLOW |
| `get_editor_state` | LOW | ALLOW |
| `get_current_file` | LOW | ALLOW |
| `open_file` | LOW | ALLOW |
| `apply_patch` | MEDIUM | ASK |
| `create_file` | MEDIUM | ASK |
| `write_file` | MEDIUM | ASK |
| `create_directory` | MEDIUM | ASK |
| `rename_file` | MEDIUM | ASK |
| `delete_file` | HIGH | ALWAYS_ASK |
| `run_terminal_command` | HIGH/CRITICAL | ASK (READ_ONLY) / ALWAYS_ASK (destructive) |

The registry is a plain map keyed by name; future phases register MCP/Browser/Git tools
through the same API without touching the runtime.

---

## 6. Permission architecture

```
ToolCallRequested → ToolExecutor
    → schema validation (untrusted AI JSON)
    → PermissionManager.resolve(tool, args, taskId)
         → policy: tool default × command classification × risk × global switch
         → ALLOW  → execute
         → ASK    → ApprovalBroker.request() (suspends) → decision
         → DENY   → ToolResult.Denied (never executes)
```

- `ToolPermission`: `ALLOW | ASK | ALWAYS_ASK | DENY`.
- `ToolRiskLevel`: `LOW | MEDIUM | HIGH | CRITICAL`.
- `PermissionScope`: `PER_REQUEST | PER_TASK` (no permanent grants in Phase 6).
- Task grants live in memory **and** in `agent_task_permissions`, and are revoked when
  the task ends, when the agent is disabled, and at app startup.
- Ordering is enforced in code: the tool body cannot run before the user decision.

### 6.1 Command classification

Commands are classified, never string-blacklisted:

| Category | Example | Policy |
|---|---|---|
| `READ_ONLY` | `ls`, `cat`, `grep`, `git status` | may auto-execute |
| `MODIFY_PROJECT` | `mkdir`, `mv`, `touch` | ASK |
| `INSTALL_PACKAGE` | `apk add`, `npm install`, `pip install` | ASK |
| `NETWORK` | `curl`, `wget`, `git clone`, `npm install` | ASK |
| `DESTRUCTIVE` | `rm`, `rm -rf`, `git reset --hard`, `apk del` | ALWAYS_ASK |

Classifier output feeds the policy; the policy is user-controlled, not a keyword blocklist.

---

## 7. Agent loop

```
goal
 → system prompt + bounded history + tool specs
 → provider turn (native tool calling)
 → no tool calls?  → COMPLETED (+ summary)
 → else: for each call: validate → permission → [approval] → execute → label result
 → append assistant(toolCalls) + tool(result) messages
 → repeat until limits
```

Limits (configurable, safe defaults): `maxIterations = 25`, `maxToolCalls = 50`,
`maxTaskDurationSeconds = 600`, `maxToolOutputChars = 24_000`, `maxFileReadLines = 400`,
`maxSearchResults = 100`. Reaching a limit produces `AgentLimitReached` with the message
*"Agent stopped because the execution limit was reached."* — never a silent continue.

---

## 8. Cancellation & process ownership

- `stop()` cancels the runtime job → cancels the in-flight provider request → cancels the
  running tool coroutine.
- Agent commands spawn a **dedicated** `Process` registered in `AgentProcessRegistry`
  under `(taskId, processId)`. `STOP`/emergency-stop terminates only agent-owned
  processes; user terminal sessions are untouched (agent processes never reuse a user
  `TerminalSession`).
- Every command has a timeout; on timeout the process is destroyed forcibly.

---

## 9. Context management

- Context budget: bounded message count, per-tool-output chars, file read window,
  search result cap, command output cap.
- Oldest tool outputs are truncated/dropped first; the most recent tool results are never
  silently discarded (they are shrunk with an explicit
  `[Output truncated. N characters omitted.]` marker).
- Tool results are labeled `[FILE CONTENT]`, `[TERMINAL OUTPUT]`, `[SEARCH RESULT]`,
  `[EDITOR STATE]` to keep data visually distinct from instructions.
- Large files return metadata + line count + suggested ranges instead of content.
- All tool output is passed through `SecretRedactor` before reaching the model or the DB.

---

## 10. Security boundaries

| Threat | Defense |
|---|---|
| Path traversal / absolute paths / symlink escape | `PathSandbox` canonical-root containment on every path arg |
| Project-root deletion | root itself and `.devstation` are protected paths |
| Unrestricted Android filesystem | tools receive only `projectRoot`; never `Context` |
| Credential access | tools have no `SecureCredentialStore` reference; redactor strips key-like strings |
| Prompt injection in files/README/terminal output | system prompt marks all tool output as untrusted data; results are labeled; project content can never authorize an action |
| Command injection via tool params | commands are passed as a single `argv` element to a process interface, never string-concatenated into a shell invocation built by the agent layer |
| Oversized requests | file read window, output cap, search cap, context budget |
| Infinite loop / tool recursion | iteration, tool-call, duration limits; tools cannot invoke tools |
| Permission bypass | single choke point (`PermissionManager`) inside `ToolExecutor`; no tool calls a process/file API directly |

---

## 11. Persistence (Room v4, additive)

- `agent_tasks(taskId, projectId, conversationId, goal, state, providerId, modelId,
  iterationCount, toolCallCount, createdAt, updatedAt, errorMessage)`
- `agent_events(id, taskId, type, label, detail, status, createdAt)`
- `agent_action_history(id, taskId, projectId, toolName, actionSummary, status, createdAt)`
- `agent_task_permissions(taskId, toolName, scope, grantedAt)`

Migration `ALTER/CREATE` only — no destructive fallback, Phase 1–5 data preserved.
`AISettingsEntity` gains `agentToolsEnabled`, `agentMaxIterations`, `agentMaxToolCalls`,
`agentMaxTaskSeconds`, `agentMaxToolOutputChars`.

---

## 12. UI

- Conversation screen gains a `[ Chat ] [ Agent ]` selector; Agent mode shows an explicit
  safety notice and requires a deliberate switch.
- Agent panel: task header, live tool timeline (✓ / ⏳ / ⚠ / ✕ per action), Stop Agent,
  approval cards (`Allow Once` / `Allow for This Task` / `Deny`), and a completion summary
  (files changed, commands executed, warnings, errors).
- Approval, AI text, and tool actions are visually distinct surfaces.
- Agent history list + task detail screen (task, project, provider, model, status,
  iterations, tool calls, duration, action history).
- Settings: global **Disable Agent Tools** switch.

---

## 13. Testing

Unit tests cover: state machine, loop limits, registry, schema validation, permission
manager, approval states, path validation, file tools, search tool, patch apply,
patch conflict detection, terminal command policy, timeout, cancellation, output limits,
secret redaction, task persistence, provider capability check, and action history.
Security tests cover traversal, absolute paths, symlink escape, root deletion, credential
access attempts, environment variable access, Android filesystem escape, parameter
injection, malicious patches, oversized file/output requests, loop bounds, tool recursion,
and permission bypass.

Verification: `./gradlew compileDebugKotlin`, `./gradlew testDebugUnitTest`,
`./gradlew assembleDebug`.

---

## 14. Explicit non-goals (deferred to later phases)

MCP, Skills, browser automation, GitHub automation, SSH/VPS/remote execution, Docker,
Kubernetes, deployment automation, autonomous background agents, scheduled agents.
