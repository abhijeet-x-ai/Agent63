# Phase 11 Implementation Plan
# Remote Workspace + SSH

## 0. Mandate

`future/remote/RemoteWorkspace.kt` defines this phase verbatim:

> "Extension contract for Phase 11 & 12: Remote Workspace, SSH, and Deployment."

Phase 11 implements the **Remote Workspace + SSH** half of that contract
(`RemoteWorkspace`, `SshConnectionConfig`). `DeploymentService` stays a
contract only — it is Phase 12 scope, together with the items the Phase 10
scope verification deferred (VPS provisioning, Docker, cloud deployment,
background daemon runners).

## 1. Architecture Audit Result

Existing integration points (all reused, nothing duplicated):

| Need | Existing component |
|---|---|
| Credential persistence (AES-256-GCM, Keystore) | `SecureCredentialStore` / `KeystoreCredentialStore` (`storeSecret`/`getSecret`/`removeSecret` by alias) |
| Database + migrations | `DevStationDatabase` (v8) + additive `Migration` pattern (`MIGRATION_6_7`, `MIGRATION_7_8`) |
| Terminal UI + ANSI pipeline | `future/terminal/` engine (`TerminalSession`, `TerminalManager`, `AnsiParser`), terminal feature screens |
| Agent tools | `Tool`/`ToolDefinition`/`ToolContext` + `ToolRegistry` (Phase 6/8 pattern, Phase 10 `GitTools.kt` precedent) |
| Command security | `TerminalSecurityPolicy.assess()` + approval chain + `SecurityAuditLogger` (Phase 7/9 pattern) |
| Path containment | `FilesystemSandbox` / `PathSandbox` |
| DI | `AppContainer` manual service locator (lazy singletons) |
| Navigation | `Screen` typed routes + `DevStationNavGraph` |
| Network | OkHttp already present (Phase 5); `INTERNET` permission already declared |

New dependency (one, wrapped behind an interface so it is replaceable):
`com.hierynomus:sshj` (Apache-2.0 SSH client: password + key auth, shell/exec
channels, SFTP) with BouncyCastle (`bcprov-jdk18on`). All sshj types stay
inside `core/remote/`; the rest of the app sees only DevStation interfaces.

## 2. Phase 11 Architecture

```
feature/remote (Compose UI)
   │  host list · host editor · trust dialog · remote terminal screen
   ▼
core/remote
   ├─ SshHostRepository      ── Room (ssh_hosts, DB v9) — profiles, no secrets
   ├─ SshCredentialManager   ── SecureCredentialStore aliases ssh/<hostId>/…
   ├─ HostKeyTrustStore      ── TOFU fingerprints (SHA256, OpenSSH format)
   ├─ SshSessionManager      ── implements future RemoteWorkspace contract
   │      ├─ SshClient (interface) → SshjClient (only sshj-aware class)
   │      ├─ exec channel: bounded output + timeout (agent tools, checks)
   │      └─ shell channel → RemoteTerminalBackend → existing TerminalSession UI
   └─ SftpSyncEngine         ── SyncPlanner (pure) + SFTP transfer (syncLocalToRemote)
Agent tools (remote_*) → ToolRegistry → policy assess → audit → SshSessionManager
```

## 3. New Package `core/remote/`

| Component | Responsibility |
|---|---|
| `RemoteModels.kt` | `SshHostProfile` (id, name, host, port, username, authType `PASSWORD`/`PRIVATE_KEY`, credentialAlias, hostKeyFingerprint?, createdAt, updatedAt, lastConnectedAt), `RemoteSessionState` (`DISCONNECTED`/`CONNECTING`/`CONNECTED`/`FAILED`), `RemoteExecResult` (exitCode, stdout, stderr, truncated), `SyncDirection`, `SyncItem`, `SyncPlan` |
| `SshHostEntity.kt` + DAO | Room entity `ssh_hosts` + `SshHostDao` (Flow list, upsert, delete, touch lastConnectedAt, set fingerprint). DB **v8 → v9**, additive `MIGRATION_8_9` |
| `SshCredentialManager.kt` | Alias scheme `ssh/<hostId>/password`, `ssh/<hostId>/key`, `ssh/<hostId>/keyPassphrase`; save/load/delete on top of `SecureCredentialStore`; deleting a host deletes its secrets |
| `HostKeyTrust.kt` | SHA-256 fingerprint of the server host key, OpenSSH-style `SHA256:<base64>` formatting/parsing; trust states `UNKNOWN`/`TRUSTED`/`CHANGED`; pure functions, unit-tested |
| `SshClient.kt` | Narrow interface (`connect`, `openShell`, `exec`, `openSftp`, `disconnect`, `isConnected`) — the seam that keeps sshj replaceable and lets tests use a fake |
| `SshjClient.kt` | sshj implementation: BouncyCastle provider registration, auth (password / key + optional passphrase), host-key verifier backed by `HostKeyTrust` (never promiscuous), keepalive, PTY shell channel, SFTP client |
| `SshSessionManager.kt` | Implements `RemoteWorkspace`: `connect(SshConnectionConfig)` resolves the stored profile + secret + trust, `disconnect()`, `isConnected: Flow<Boolean>`; single active session; exec with bounded output (default 24k chars, matching agent limits) and timeout; session state machine with `FAILED` + reason; audit events on connect/exec/disconnect |
| `RemoteTerminalBackend.kt` | Bridges an SSH shell channel's streams to the terminal engine's session I/O so the existing terminal composables render remote shells unchanged (resize → `window-change` PTY request) |
| `SyncPlanner.kt` | Pure: given local file listing (path, size, mtime) and remote listing, compute the minimal upload set (new/changed only), honoring excludes (`.git/`, build outputs) and a per-file size cap; unit-tested without any SSH |
| `SftpSyncEngine.kt` | Executes a `SyncPlan` over SFTP: recursive mkdir, upload with progress `Flow`, per-file failure isolation, summary result; local side resolved through `FilesystemSandbox` only |
| `RemoteTools.kt` | Agent tools in `GitTools.kt` style: `remote_connect`, `remote_exec`, `remote_sync_push`, `remote_disconnect`; every exec passes `TerminalSecurityPolicy.assess()` and is audit-logged |

## 4. New Feature UI `feature/remote/`

| Screen | Content |
|---|---|
| `RemoteHostsScreen` | Saved hosts list (name, `user@host:port`, last connected, trust badge), Connect / Edit / Delete, empty state, "Add host" FAB; entry point from Home quick tools + bottom nav unchanged |
| `RemoteHostEditScreen` | Form: name, host, port (default 22), username, auth type toggle (password / private key + optional passphrase); validation (host non-blank, port 1–65535); secrets written only via `SshCredentialManager` |
| `HostKeyTrustDialog` | First-connect fingerprint display (`SHA256:…`, key type) with Trust / Cancel; changed-key hard warning with the old + new fingerprints |
| `RemoteTerminalScreen` | Existing terminal UI bound to `RemoteTerminalBackend`; disconnect action; session state banner (connecting/failed reason) |

Navigation: `Screen.RemoteHosts`, `Screen.RemoteHostEdit(hostId?)`,
`Screen.RemoteTerminal(hostId)` routes added to `DevStationNavGraph`.

## 5. Security Boundaries

* **Secrets**: passwords, private keys, and passphrases exist only inside
  `SecureCredentialStore` (Keystore AES-256-GCM). Room stores profiles and
  public host-key fingerprints only — never secrets.
* **Host keys**: TOFU is mandatory. First connect requires an explicit user
  trust decision on the shown fingerprint. A changed key fails the connection
  closed and surfaces both fingerprints. No promiscuous verifier anywhere.
* **Agent access**: remote tools go through the same policy + approval +
  audit chain as local terminal tools. `remote_exec` output is bounded and
  secret-redacted like local command output.
* **Sync containment**: local paths resolve through `FilesystemSandbox`
  (no traversal/symlink escape, project-scoped); remote paths are normalized
  and rejected if they escape the configured remote root via `..`.
* **Transport**: SSH only (no telnet/plain sockets); algorithms restricted to
  sshj/BouncyCastle defaults with strong ciphers; no credential is ever
  logged (`SecretRedactor` on all remote log lines).

## 6. Build & Release Changes

* `app/build.gradle.kts`: add sshj + BouncyCastle dependencies;
  version bump **versionCode 8 / versionName 1.2.0**.
* `DevStationDatabase`: version 9 + `MIGRATION_8_9` (additive `ssh_hosts`).
* `.github/workflows/build-apk.yml`: release tag/title/notes updated
  v1.1.5 → **v1.2.0** so CI publishes the Phase 11 build correctly.
* `AppContainer`: lazy providers for repository, credential manager,
  session manager, sync engine.

## 7. Testing

New JVM unit suites (existing 564 tests must stay green):

* `HostKeyTrustTest` — fingerprint formatting/parsing, trust-state transitions
* `SyncPlannerTest` — new/changed/unchanged selection, excludes, size cap, remote-path normalization
* `SshCredentialManagerTest` — alias scheme, save/load/delete cascade (fake store)
* `SshSessionManagerTest` — state machine with a fake `SshClient` (connect success/failure, exec bounds, disconnect), trust-required failure path
* `SshHostValidationTest` — profile form validation rules

Verification is the repo CI (`.github/workflows/build-apk.yml`):
`testDebugUnitTest` + `assembleDebug` on the Phase 11 branch state, then the
same on `main` for the release publish. On-device SSH behavior (real server
handshake, PTY behavior) is **not** claimed by CI and is listed as a manual
verification step in the Phase 11 build report.

## 8. Scope Verification (Phase 12 NOT in this phase)

* NO `DeploymentService` implementation (contract only — Phase 12).
* NO VPS provisioning, Docker, or cloud deployment.
* NO port forwarding / tunneling UI, NO jump hosts (single-hop SSH only).
* NO background daemon/agent runners; sessions are foreground, user-driven.
* NO changes to Phase 1–10 behavior; all existing tests must pass unmodified.
