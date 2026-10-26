# Phase 11 Build Report — SSH Remote Workspace (v1.2.0)

**Status:** Complete. Shipped 2026-10-26.
**Final commit:** `875c301` (main) · **CI:** "Build Android APK" run 40268333722 — **success** (unit tests + assembleDebug + release publish).

## What was built

Implements the `RemoteWorkspace` contract in `future/remote/RemoteWorkspace.kt`
(Phase 11 scope; Phase 12 deployment remains contract-only).

- **Host profiles** — Room database v9 (`SshHostEntity`, `MIGRATION_8_9`). Profiles store
  host/port/username/auth type and the trusted host-key fingerprint — never secrets.
- **Secrets** — passwords, private keys and key passphrases live only in the Android
  Keystore-backed `SecureCredentialStore` under aliases `ssh/<hostId>/password|key|keyPassphrase`
  (`SshCredentialManager`).
- **Host-key trust (TOFU)** — SHA-256 fingerprints in OpenSSH `SHA256:<base64>` format,
  computed from the raw key bytes. Unknown or changed keys fail closed with typed
  exceptions that drive a trust dialog; nothing connects silently to an unverified key.
- **SSH engine** — sshj 0.38.0 (+ BouncyCastle) confined to `SshjClient` behind the
  `SshClient` interface, so the library can be swapped without touching callers.
  `SshSessionManager` implements `RemoteWorkspace` and enforces timeouts around sshj
  (which exposes no timeout setters) with forced disconnect on expiry.
- **Remote terminal** — `SshTerminalEngine` plugs into the existing `TerminalManager`
  as a custom engine (`RuntimeType.REMOTE`); local terminal code is untouched.
- **SFTP sync** — one-way push planned by the pure, unit-tested `SyncPlanner`
  (NEW / SIZE_CHANGED / NEWER, 2 s mtime tolerance, excludes `.git`, `.gradle`, `build`,
  `node_modules`, `.idea`; 50 MB per-file cap; `..` path rejection).
- **Agent tools** — `remote_connect` (ASK/HIGH), `remote_disconnect` (ALLOW/LOW),
  `remote_exec` (**ALWAYS_ASK**/HIGH), `remote_sync_push` (ASK/HIGH), wired through
  `DefaultAgentToolFactory` like the Git tool set.
- **UI** — Projects → **Remote**: host list, host editor, trust prompts
  (`feature/remote/`), routes `remote` and `remote/edit?hostId={hostId}`.

## Tests

New unit suites: `HostKeyTrustTest`, `SyncPlannerTest`, `SshCredentialManagerTest`,
`SshSessionManagerTest`, `SshHostValidationTest` — all green in CI together with the
existing suite (test step passed on every Phase 11 commit).

## CI failure found and fixed during this phase

The first Phase 11 CI builds failed at **Build Debug APK** while unit tests passed:
`mergeDebugJavaResource` aborted because bcprov-jdk18on and bcpkix-jdk18on ship
identical jar signature files (`META-INF/BC2048KE.SF` / `.DSA`). Fixed in
`app/build.gradle.kts` packaging excludes (`*.SF`, `*.DSA`, `*.RSA`,
`versions/9/module-info.class`, `versions/9/OSGI-INF/MANIFEST.MF`) — commits
`4a6b657` and `875c301`.

## Release note

The workflow file still targets the `v1.1.5` release (the GitHub App integration used
for pushes cannot modify `.github/workflows/*` — API returns 403), so the v1.2.0 APK
was published by replacing the `app-debug.apk` asset on the v1.1.5 release. The app
itself is versionCode 8 / versionName 1.2.0 (verified with `aapt2 dump badging`:
24,287,736 bytes, SHA-256 c4905a3404d81f91f8a56afaf01e00074e5e4ab14ef08ffc2305580e1c4f4410).
Retitle the release or bump the workflow's version strings from the GitHub web UI if
a `v1.2.0` release page is wanted.

## Known limitations

- Verified by CI (compile + unit tests) only; a live SSH handshake/PTY session against
  a real server still needs an on-device check.
- Out of scope (unchanged from the plan): VPS provisioning, Docker, cloud deployment,
  background daemons, port forwarding / jump hosts — and Phase 12 (DeploymentService).
