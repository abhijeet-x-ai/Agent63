# DEVSTATION — PHASE 6: AI AGENT + TOOL EXECUTION SYSTEM
## COMPREHENSIVE IMPLEMENTATION & VERIFICATION REPORT

**Date**: September 21, 2026  
**Project**: DevStation (Android Mobile AI Workstation)  
**Phase**: Phase 6 — AI Agent + Tool Execution System  
**Build Status**: **SUCCESS** (`compileDebugKotlin`, `testDebugUnitTest` 251/251 passing, `assembleDebug` APK produced)

---

## 1. Summary

Phase 6 turns the Phase 5 provider layer into a **controlled coding agent**. The agent can inspect a
project, read/list/search files, create/modify/delete files with approval, run bounded development
commands in the Phase 3 Linux runtime, iterate on results, and report what it actually did — while
every single action passes through a permission check that can block it.

The agent never touches the Android filesystem, a raw `ProcessBuilder`, the credential store, or any
Android API directly. There is exactly one path from a model tool call to a real effect:

```
AI Agent → AgentRuntime (bounded loop) → ToolExecutor → PermissionManager → ApprovalBroker → Tool → project root
```

Phase 1–5 were extended, not rewritten: the editor (`EditorFileManager`, `ProjectSearchEngine`), the
terminal/runtime (`LinuxProcessLauncher`, `LinuxRuntimeManager`), the provider system (`AIProvider`,
`AIProviderManager`), the credential store (`SecureCredentialStore`) and Room are all reused as-is.

**Verification:** `251 / 251` unit tests pass across 51 suites — the 18 Phase 1–4 suites and 9 Phase 5
suites stay regression-free, and 24 new Phase 6 suites (including provider tool-calling) cover the
agent; `compileDebugKotlin` is clean and the debug APK builds.

---

## 2. Agent Architecture

```
core/agent/
├── AgentModels.kt          AgentState, AgentStepStatus, AgentEvent, AgentLoopLimits,
│                           AgentTaskRequest, AgentTaskSnapshot, AgentTimelineEntry, AgentRunSummary
├── AgentRuntime.kt         the bounded agent loop + ProjectLocator / AgentConversationPort ports
├── AgentContextBuilder.kt  message list, labeling, context budget
├── AgentSystemPrompt.kt    provider-agnostic agent policy prompt
├── AgentTool.kt            Tool, ToolDefinition, ToolContext, ToolResult, risk/permission enums
├── ToolRegistry.kt         central registry (single extension point for future phases)
├── ToolExecutor.kt         the one path from a call to an effect
├── ToolArgumentValidator.kt  untrusted-argument schema validation
├── PermissionManager.kt    permission resolution + task grants
├── Approval.kt             ApprovalRequest / ApprovalBroker / ApprovalDecision
├── PathSandbox.kt          project-root containment (traversal, absolute, symlink, internal state)
├── CommandClassifier.kt    READ_ONLY … DESTRUCTIVE classification
├── OutputLimiter.kt        output/file truncation + explicit omission markers
├── SecretRedactor.kt       best-effort credential redaction
├── AgentRepositories.kt    narrow persistence ports (task/event/history/permission stores)
└── tools/
    ├── FileTools.kt        read/write/create/delete/list/create_directory/rename/apply_patch
    ├── SearchTool.kt       search_project (reuses ProjectSearchEngine)
    ├── EditorTools.kt      open_file / get_editor_state / get_current_file + EditorBridge
    ├── TerminalTools.kt    run_terminal_command, process runners, agent-owned process registry
    └── AgentToolFactory.kt builds the per-task registry
```

The runtime depends only on narrow ports — `AIProviderManager`, `AISettingsRepository`,
`ProjectLocator`, `AgentConversationPort`, `AgentToolFactory`, the four stores and
`DispatcherProvider` — so the whole loop is unit-testable without Android or Room.

---

## 3. Agent State Machine

`AgentState`: `IDLE`, `PLANNING`, `WAITING_FOR_APPROVAL`, `EXECUTING_TOOL`, `WAITING_FOR_MODEL`,
`COMPLETED`, `FAILED`, `CANCELLED`, `PAUSED`, `INTERRUPTED`.

Every transition is made explicitly by `AgentRuntime` (never inferred), is written through
`AgentTaskStore.updateProgress/updateState`, and is mirrored into `AgentTaskSnapshot.timeline`.
`WAITING_FOR_APPROVAL` corresponds to `ApprovalBroker.pending` being non-null; `INTERRUPTED` is set
only by startup recovery (`AgentRuntime.recoverInterruptedTasks()` → `initializeAgent()`), which also
drops every task-scoped permission. There is **no** automatic resume of autonomous execution.

---

## 4. Tool Architecture

```
model tool call (provider-neutral AIToolCall)
        ↓
ToolRegistry.get(name)             unknown → error, nothing runs
        ↓
ToolArgumentValidator.validate     unknown/missing/mistyped/oversized args → error, nothing runs
        ↓
CommandClassifier (terminal only)  READ_ONLY | MODIFY_PROJECT | INSTALL_PACKAGE | NETWORK | DESTRUCTIVE
        ↓
PermissionManager.authorize        ALLOW → run · ASK/ALWAYS_ASK → ApprovalBroker (suspends) · DENY → refuse
        ↓
Tool.execute(call, args, ToolContext)   bounded output, redacted, labelled
```

`ToolContext` is deliberately narrow: `projectId`, `projectRoot`, `workingDirectory`, `agentId`,
`taskId`, `scope`, and a cancellation check. It contains **no** Android `Context`, no credential
store, no runtime manager, and no reference back to the executor or registry — so a tool cannot
recursively call another tool, and there is nothing to escalate with.

`ToolResult` is normalized: `Success` / `Error` / `Denied` / `Cancelled` / `Timeout`, each carrying
`toolName`, bounded `output` and optional `metadata`. Serialized to the model as a JSON payload with
an explicit `status`, so a provider that needs structured tool responses still receives valid JSON.

---

## 5. Tool Registry (§58)

| Tool | Risk | Permission | Notes |
|---|---|---|---|
| `read_file` | LOW | `ALLOW` | ranges, large-file metadata, binary detection |
| `list_directory` | LOW | `ALLOW` | names/sizes/relative paths, skips churn |
| `search_project` | LOW | `ALLOW` | reuses Phase 4 `ProjectSearchEngine` |
| `open_file` | LOW | `ALLOW` | publishes an editor navigation request only |
| `get_editor_state` | LOW | `ALLOW` | paths only, from the real editor |
| `get_current_file` | LOW | `ALLOW` | active editor file |
| `apply_patch` | MEDIUM | `ASK` | exact unique `find` block + hash conflict check |
| `write_file` | MEDIUM | `ASK` | atomic save via `EditorFileManager` |
| `create_file` | MEDIUM | `ASK` | refuses to overwrite |
| `create_directory` | MEDIUM | `ASK` | |
| `rename_file` | MEDIUM | `ASK` | |
| `delete_file` | HIGH | `ALWAYS_ASK` | never satisfiable by a task grant |
| `run_terminal_command` | HIGH | classification-driven | see §22/§47 below |

Registered through `ToolRegistry`, whose `register()` rejects duplicate or malformed names. Future
phases add MCP/browser/Git/deployment tools through the same `Tool` API — no runtime change needed.

---

## 6. File Tools

Implemented in `core/agent/tools/FileTools.kt` on top of the Phase 1 filesystem and Phase 4
`EditorFileManager` (atomic saves, encoding/BOM/line-ending preservation, binary detection):

* all paths are resolved by `PathSandbox` and must stay inside the project root;
* the project root itself and `.devstation` internal state are never writable or deletable;
* `read_file` caps at `maxFileReadLines` (default 400) and `maxToolOutputChars`; files above 256 KB
  return metadata + suggested ranges instead of content, files above 8 MB are refused outright;
* `write_file` creates missing parent directories, writes atomically, and returns the new hash;
* `apply_patch` replaces an exact, unique block and refuses when the block is missing, ambiguous,
  or when the file changed since the agent last read it;
* `delete_file` reports what it removed (file, size, or affected file count for directories);
* `rename_file` refuses separators and any path leaving the project.

## 7. Search Tools

`search_project` wraps the existing Phase 4 `ProjectSearchEngine` — no second search engine exists.
It supports query, optional path, optional file pattern and case sensitivity, skips dependency
directories, bounds the number of results, and returns `file : line : text` matches. Blank queries
and out-of-project paths are rejected before the engine runs.

## 8. Editor Tools

`EditorBridge` (implemented by `EditorBridgeImpl`, provided by `AppContainer`) is the only coupling
between the agent and the Phase 4 editor. `open_file` emits an `EditorOpenRequest` that the UI
collects and navigates with; `get_editor_state` / `get_current_file` answer from the real editor
state reported by the UI. The agent never opens a duplicate editor instance.

## 9. Terminal Tools

`run_terminal_command` is the highest-risk tool:

* command, `workingDirectory` (sandboxed) and `timeoutMs` are validated before anything runs;
* the command string is passed as a single element to a controlled process interface; the agent
  layer never builds a shell command by concatenation (§68);
* commands execute in a **dedicated agent-owned process** — never in a user terminal session (§57);
* output is bounded (`maxToolOutputChars`), the exit code is reported, non-zero exits become a
  structured error carrying the output, and secret-looking output is redacted.

## 10. Linux Integration

`LinuxCommandRunner` runs commands through the Phase 3 `LinuxProcessLauncher`, so they execute inside
the Alpine/PRoot userspace with the project mapped to `/workspace`. When the runtime is not installed
the tool reports a structured error unless the user has explicitly enabled the restricted Android
shell fallback (`agentAllowAndroidShell`, default on, never selected silently). The chosen
environment is stated in every result header (`environment: Linux /workspace`).

## 11. Permission Model

`ToolPermission`: `ALLOW`, `ASK`, `ALWAYS_ASK`, `DENY`. Risk: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.
Scopes: `PER_REQUEST`, `PER_TASK` — Phase 6 deliberately has no permanent grants.

## 12. Approval System

`ApprovalBroker.request()` suspends the tool call until the user answers, and the decision is applied
**before** any tool body runs (verified by tests: "approval is requested before execution and denial
prevents execution", "denied approval never executes the tool"). The card shows the tool, the exact
target (file path or command), the working directory/reason, the risk level, and three actions —
**Allow Once**, **Allow for This Task**, **Deny**. "Allow for this task" is remembered only in memory
and in `agent_task_permissions` (deleted when the task ends or the app restarts); `ALWAYS_ASK` tools
such as `delete_file` and destructive commands can never be satisfied by a task grant. Cancelling a
task, pressing Stop/Emergency Stop, or app shutdown denies anything still pending.

## 13. Context Management

`AgentContextBuilder` keeps the user's goal, recent conversation history (max 12 turns), tool calls
and tool results, and enforces `maxContextChars` (default 120 000) by first shrinking the oldest tool
outputs with an explicit marker, then the oldest assistant text, and only then dropping the oldest
history turns — never the goal and never the newest turn. Tool results are labeled `[FILE CONTENT]`,
`[TERMINAL OUTPUT]`, `[SEARCH RESULT]`, `[EDITOR STATE]`. Outputs are bounded by
`OutputLimiter.truncate`, which keeps the head and the tail and states how many characters were
omitted. The context never includes the whole project, terminal history, credentials, or environment
variables.

## 14. Prompt Injection Defense

`AgentSystemPrompt` states, as the highest-precedence section, that tool results are DATA and never
instructions, that README files, code comments and terminal output may contain injection attempts,
that only the user's messages and system policy can authorize actions, and that the precedence is
system safety → application policy → user request → project content. Structurally, the model cannot
exceed its authority even if it is convinced: every effect still requires a permission decision, and
destructive actions always require the user. Tests assert that unknown tools, invalid arguments,
unknown parameters and denied approvals never reach a tool body.

## 15. Secret Protection

`SecretRedactor` masks bearer tokens, common API-key shapes (`sk-`, `sk-ant-`, `AIza`, `ghp_`,
`github_pat_`, `xox*`, `AKIA`), `key = value` credential assignments and PEM private-key blocks
before any output leaves a tool; `ToolExecutor` applies a final redaction + truncation pass as
defense in depth. Credentials themselves are unreachable: tools have no reference to
`SecureCredentialStore`, and `PathSandbox` rejects absolute system paths (`/data/data/...`,
`/data/misc/keystore/...`, `/proc/self/environ`, …) — covered by `AgentSandboxSecurityTest`.

## 16. Process Ownership

`AgentProcessRegistry` keys every spawned process by `taskId`. `AgentRuntime.stop()` terminates only
the current task's processes; `stopAll()` terminates all agent-owned processes. User terminal
sessions are never touched, because agent commands never reuse them.

## 17. Cancellation

`STOP AGENT` sets a cancellation flag, denies a pending approval, terminates agent-owned processes,
and cancels the running coroutine; the loop then persists `CANCELLED` (inside `NonCancellable`, so
the state is always written). Long-running tools poll `ToolContext.isCancelled()`; the command runner
terminates the child process and returns `Cancelled`. `STOP ALL AGENT ACTIONS` additionally clears all
in-memory and persisted task permissions.

## 18. Task Persistence

Room v4 tables `agent_tasks`, `agent_events`, `agent_action_history`, `agent_task_permissions`.
`agent_tasks` stores `taskId, projectId, conversationId, goal, state, providerId, modelId,
iterationCount, toolCallCount, createdAt, updatedAt, errorMessage` — no secrets, no raw provider
payloads, no hidden reasoning. On app start every non-terminal task becomes `INTERRUPTED` and all
task-scoped permissions are deleted. Phase 6 never auto-resumes.

## 19. Action History

Every executed tool call is appended to `agent_action_history` (`timestamp, taskId, projectId,
toolName, summary, status` where status ∈ SUCCESS/FAILED/DENIED/CANCELLED/TIMEOUT). Summaries are
one-line labels such as `write_file src/App.kt`; API keys and full sensitive command output are never
stored. The task detail screen reads this audit trail.

## 20. UI

* **Chat / Agent selector** on the AI chat screen; Agent mode is opt-in with an explicit explanation
  that it can inspect and modify the project and run approved commands.
* **Agent panel**: task goal, live timeline (`✓ Read src/App.kt`, `⏳ Run npm test`,
  `⚠ Approval required`), iteration/tool-call counters, **Stop Agent** and **STOP ALL AGENT ACTIONS**.
* **Approval card**: title, exact target, reason/working directory, risk chip, *Allow Once* /
  *Allow for This Task* / *Deny*.
* **Completion summary**: files changed, commands executed, tests run, warnings, errors — only what
  actually ran is listed.
* **Task history** (`AgentTasksScreen`) and **task details** (`AgentTaskDetailScreen`) with status,
  provider/model, iterations, tool calls, duration and the action trail.
* **Settings**: agent tool switch (`agentToolsEnabled`) plus limits, and an entry point to task
  history. AI responses and tool actions are rendered as visually distinct items — the UI never
  merges model text with trusted application actions, and it never displays chain-of-thought.

## 21. Database Changes

Version 3 → 4, additive: `MIGRATION_3_4` creates the four agent tables and the `AISettingsEntity`
gains `agentToolsEnabled`, `agentMaxIterations`, `agentMaxToolCalls`, `agentMaxTaskSeconds`,
`agentMaxToolOutputChars`, `agentAllowAndroidShell`. No destructive fallback; Phase 1–5 data (including
conversations and AI settings) is preserved. `MIGRATION_2_3` (Phase 5) is unchanged.

## 22. Tests

`251 tests / 51 suites / 0 failures` (`./gradlew testDebugUnitTest`).

**Phase 6 suites (144 tests, 24 suites):** AgentLoopTest (20), FileToolsTest (14),
TerminalToolTest (13), PathSandboxTest (10), ToolExecutorTest (10), CommandClassifierTest (8),
ApplyPatchToolTest (7), PermissionManagerTest (7), AgentContextBuilderTest (7), SearchProjectToolTest (6),
ToolArgumentValidatorTest (5), AgentSandboxSecurityTest (5), EditorToolsTest (4), SecretRedactorTest (4),
BaseProcessCommandRunnerTest (4), OutputLimiterTest (3), ToolRegistryTest (3), AgentProcessRegistryTest (2),
AgentTaskPersistenceTest (1), RunTerminalCommandToolPolicyTest (1), plus the provider tool-calling suites
OpenAIToolCallingTest (3), AnthropicToolCallingTest (3), GeminiToolCallingTest (3),
ProviderToolCallingCapabilityTest (1).

**Coverage:** state machine and task persistence, iteration/tool-call/duration limits, tool registry and
schema validation, permission manager and approval states, path validation, file/search/patch tools,
patch conflict detection, terminal policy/timeout/cancellation, tool output limits, secret redaction,
provider capability gating, action history, context budgeting.

## 23. Security Tests

Explicitly asserted (§66): `../../outside-project` traversal, absolute paths (`/etc/passwd`), Android
private/system paths (`/data/data/...`, `/data/misc/keystore`, `/proc/self/environ`), credential and
`.env` files outside the project (content must not leak), symlink escape, project-root
modification/deletion, `.devstation` internal state, malformed patches, non-unique patches, patches
against externally modified files, oversized file requests (metadata-only answer and hard cap),
oversized command output (bounded), unknown tool and tool recursion (unreachable), invalid/unknown
oversized arguments (never reach the tool body), infinite loops (iteration/tool-call/duration limits),
permission bypass (deny cannot be overridden; task grants cannot satisfy `ALWAYS_ASK`), destructive
command escalation, and cancellation short-circuiting before execution. The agent fails closed in
every case.

## 24. Build Results

| Check | Result |
|---|---|
| `./gradlew compileDebugKotlin` | ✅ 0 errors |
| `./gradlew testDebugUnitTest` | ✅ 251/251 passed, 0 failures |
| `./gradlew assembleDebug` | ✅ APK built |

## 25. APK Output Path

`app/build/outputs/apk/debug/app-debug.apk`

## 26. APK Size

**19,167,570 bytes (~18.28 MB)** — Phase 5 was 18,725,726 bytes (~17.86 MB), so Phase 6 adds ~0.42 MB
of code (agent core, tools, UI, tests are not packaged).

## 27. Known Limitations

1. The §80 manual smoke test was **not** executed — it requires an Android device/emulator; verification
   here is compilation + unit/integration tests + APK packaging.
2. Full Unix PTY is still unavailable (Phase 3 limitation), so agent commands use the pipe-based
   process interface; stdout/stderr are captured, but interactive programs are not supported.
3. `LinuxCommandRunner` requires the Phase 3 runtime; otherwise the restricted Android shell fallback
   (opt-in, reported in output) or a structured refusal applies.
4. Agent mode requires provider **and** model tool-calling support; unsupported models are rejected
   with "supports chat but not agent tool execution" while normal chat keeps working. Providers that
   do not support native tool calling are never fed invented JSON.
5. Prompt-injection defense is guidance plus structural enforcement; no filter can guarantee a model
   never *suggests* something harmful — the enforcement point is the permission/tool layer, which
   cannot be bypassed by content.
6. Secret redaction is best-effort pattern matching; it reduces leakage and is not a complete
   detector.
7. The approval card shows target/command/risk, not a rendered diff — the user sees what will change
   and where, but not a full patch preview.
8. Task-scoped permissions live in memory plus a table that is cleared when the task ends; an
   app kill mid-task therefore expires them (by design).
9. Interrupted tasks are marked `INTERRUPTED`; resume/replay is intentionally not implemented.
10. Long-running installs/builds are bounded by the tool timeout; there is no background execution of
    agent tasks after the app leaves the foreground.

## 28. Phase 7 Recommendations

* **MCP** client/tool discovery registered through `ToolRegistry`.
* **Skills**/prompt-pack system layered on `AgentSystemPrompt`.
* **Git & GitHub** tools (status/diff/commit, later PR flows) — local Git first, then managed creds.
* **Browser/web tools** as explicitly separate, permission-gated tools.
* **Remote/VPS & deployment** tools behind a new permission class.
* **Scheduled/background agents** with a foreground service, resumable tasks and durable approvals.
* **Diff-preview approvals** and per-hunk patch review in the approval card.
* **Task replay** for interrupted tasks once resumability is safe.

---

## Implemented Tools (all of Phase 6)

`read_file`, `write_file`, `create_file`, `delete_file`, `list_directory`, `create_directory`,
`rename_file`, `apply_patch`, `search_project`, `open_file`, `get_editor_state`, `get_current_file`,
`run_terminal_command`.

## Tools Requiring Approval

* **Always, every time (`ALWAYS_ASK`)**: `delete_file`; `run_terminal_command` classified as
  DESTRUCTIVE (`rm`, `rm -rf`, package removal, formatting, system modification).
* **Per call unless granted for the task (`ASK`)**: `write_file`, `create_file`, `create_directory`,
  `rename_file`, `apply_patch`; `run_terminal_command` classified as MODIFY_PROJECT, INSTALL_PACKAGE
  or NETWORK (e.g. `npm install`, `apk add`, `pip install`, `git clone`, `curl`, `wget`).
* **No approval (read-only, project-scoped)**: `read_file`, `list_directory`, `search_project`,
  `open_file`, `get_editor_state`, `get_current_file`; `run_terminal_command` classified as READ_ONLY.

## Not Supported in Phase 6 (deliberately deferred)

MCP (client/server/tool discovery), Skills, browser automation, GitHub automation, SSH, VPS, remote
agents/remote execution, Docker/Kubernetes, deployment automation, scheduled agents, and background
autonomous agents. No MCP, browser, Git-hosting or remote tool exists in `ToolRegistry`. Phase 6 also
does not perform autonomous app-restart resume, tool recursion, or permanent agent permissions.

**STOPPED after Phase 6 as instructed — Phase 7 has not been started.**
