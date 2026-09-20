# DevStation — Phase 3 Implementation Plan
## Real Linux Userspace Runtime & Development Environment

### 1. Existing Architecture Overview

DevStation is structured across clean, modular layers:
- **Phase 1 Foundation**:
  - `core/model`: Domain entities (`Project`, `Conversation`, `Message`, `AppSettings`, `StorageStats`).
  - `core/database`: Room DB (`DevStationDatabase`), entities, and DAOs.
  - `core/filesystem`: `ProjectFileSystemManager` managing real project directories in app storage (`/projects`), and `StorageStatsCalculator` reporting disk statistics.
  - `core/security`: `KeystoreCredentialStore` with Android Keystore AES-256 GCM.
  - `core/ui`: Responsive Jetpack Compose Material 3 theme, adaptive layout (phone portrait bottom bar, tablet/landscape rail).
  - `core/di`: `AppContainer` providing manual dependency injection.
- **Phase 2 Terminal & Process Management**:
  - `future/terminal/TerminalEngine`: Contract for terminal process lifecycle, stream I/O, signals.
  - `future/terminal/LocalAndroidTerminal`: Pipe-bridge Android OS process engine using `ProcessBuilder`.
  - `future/terminal/TerminalSession`: Manages 5,000-line bounded output buffer and secret-filtered command history.
  - `future/terminal/TerminalManager`: Multi-session manager supporting creation, switching, stopping, and closing.
  - `future/terminal/AnsiParser`: Converts ANSI color/style sequences to Compose `AnnotatedString`.
  - `future/terminal/ShellDetector`: Probes `/system/bin/sh` and `/system/bin/mksh`.
  - `feature/terminal/`: `TerminalScreen`, `TerminalConsole`, `TerminalKeyboardBar`, `TerminalSessionTabs`, `TerminalViewModel`.

---

### 2. Proposed Linux Runtime Architecture

```
                               ┌─────────────────────────────┐
                               │       DevStation App        │
                               │      (Android Sandbox)      │
                               └──────────────┬──────────────┘
                                              │
                 ┌────────────────────────────┼────────────────────────────┐
                 ▼                                                         ▼
    ┌──────────────────────────┐                             ┌──────────────────────────┐
    │     Terminal Screen      │                             │   Linux Runtime Screen   │
    │ (Runtime: Android/Linux) │                             │(Install/DevTools/Status) │
    └────────────┬─────────────┘                             └─────────────┬────────────┘
                 │                                                         │
                 ▼                                                         ▼
    ┌──────────────────────────┐                             ┌──────────────────────────┐
    │     TerminalManager      │                             │   LinuxRuntimeManager    │
    │(Android & Linux Sessions)│                             │(State/Lifecycle/Storage) │
    └────────────┬─────────────┘                             └─────────────┬────────────┘
                 │                                                         │
                 ▼                                                         ▼
    ┌──────────────────────────┐                             ┌──────────────────────────┐
    │      TerminalEngine      │                             │   LinuxRootfsInstaller   │
    ├──────────────────────────┤                             │  (Download/Verify/Tar)   │
    │ ├── LocalAndroidTerminal │                             └─────────────┬────────────┘
    │ └── LinuxTerminalEngine  │                                           │
    └────────────┬─────────────┘                             ┌─────────────┴────────────┐
                 │                                           │  LinuxPackageManager     │
                 ▼                                           │  (Alpine apk / packages) │
    ┌──────────────────────────┐                             ├──────────────────────────┤
    │    LinuxProcessLauncher  │                             │  DevToolsInstaller       │
    │(PRoot / Userspace bridge)│                             │  (Node, Python, Git)     │
    └────────────┬─────────────┘                             └──────────────────────────┘
                 │
                 ▼
    ┌───────────────────────────────────────────────────────────────────────────────────┐
    │                            Local Persistent Storage                               │
    │  ├── Rootfs: <app_files>/linux/rootfs/ (Alpine Linux userspace)                   │
    │  ├── HOME:   <app_files>/linux/home/devstation/ (persistent user config)          │
    │  └── Bind:   <app_files>/projects/<project>/ ──► mounted to /workspace            │
    └───────────────────────────────────────────────────────────────────────────────────┘
```

#### Key Architecture Tenets:
1. **Unprivileged Userspace Only**:
   - Runs exclusively within the Android application sandbox.
   - Zero root requirements, zero `su` invocations, zero tampering with Android `/system` or `/vendor`.
   - Uses PRoot command model (fake root `-0`, bind mounts `-b`, chroot `-r`, working dir `-w`) or direct userspace bridge.
2. **Alpine Linux 3.19 (Minirootfs)**:
   - Official release: Alpine Linux 3.19.1 minirootfs (`dl-cdn.alpinelinux.org`).
   - Supported ABIs: `arm64-v8a` (`aarch64`) primary, `x86_64` fallback.
   - Package manager: native `apk` (`apk add nodejs npm python3 py3-pip git`).
   - Official SHA-256 verification and configurable manifest abstraction.
3. **Workspace as Source of Truth**:
   - DevStation projects directory (`/projects/<activeProject>`) is mounted to `/workspace` inside Linux.
   - Edits in Linux appear immediately in DevStation file browser; files created in DevStation appear in Linux.
4. **Persistent HOME**:
   - Located at `<app_files>/linux/home/devstation/`, mounted to `/home/devstation`.
   - Survives terminal restarts, app updates, and session switches.
5. **Unified Terminal Integration**:
   - `TerminalScreen`, `TerminalConsole`, `TerminalSessionTabs`, and `TerminalKeyboardBar` remain intact.
   - Runtime selector allows switching between **Android Shell** (Phase 2) and **Linux Environment** (Phase 3).
   - Linux sessions use `LinuxTerminalEngine` implementing `TerminalEngine`.

---

### 3. Detailed Component Design & Files

#### A. Runtime Core (`future/runtime/`)
1. **`future/runtime/LinuxRuntimeModels.kt`** [NEW]:
   - `LinuxRuntimeState`: `NOT_INSTALLED`, `DOWNLOADING`, `VERIFYING`, `EXTRACTING`, `CONFIGURING`, `BOOTSTRAPPING`, `READY`, `FAILED`, `CANCELLED`, `UPDATING`, `REMOVING`.
   - `LinuxDistro`: `ALPINE`, `DEBIAN`, `UBUNTU`.
   - `CpuArchitecture`: `ARM64`, `X86_64`, `UNSUPPORTED`.
   - `RootfsManifest`: URL, checksum, checksumAlgorithm, distro, version, architecture, archiveSize, installedSize.
   - `LinuxEnvironmentConfig`: HOME, PATH, TERM, LANG, workspaceDir, rootfsDir, homeDir.
   - `LinuxDiagnostics`: System probe results (arch, rootfs, shell, home, workspace, PATH, apk, node, python, git, pty, internet).
   - `PackageInfo`, `PackageManagerStatus`.
2. **`future/runtime/CpuArchitectureDetector.kt`** [NEW]:
   - Inspects `android.os.Build.SUPPORTED_ABIS`.
   - Resolves `arm64-v8a` to `CpuArchitecture.ARM64` (`aarch64`), `x86_64` to `CpuArchitecture.X86_64`.
3. **`future/runtime/RootfsManifestRegistry.kt`** [NEW]:
   - Provides verified official Alpine Linux 3.19 minirootfs manifests for `aarch64` and `x86_64` with official SHA-256 checksums.
   - Allows custom/local offline manifests for development and testing.
4. **`future/runtime/TarExtractor.kt`** [NEW]:
   - Pure Kotlin/Java streaming `.tar.gz` extractor using `GZIPInputStream`.
   - **Path Traversal Security**: Rejects entries containing `..`, absolute paths leading outside destination, or escaping symlinks.
   - Validates canonical path stays strictly within `targetDir`.
   - Preserves executable file modes where permitted by the Android filesystem.
5. **`future/runtime/LinuxRootfsInstaller.kt`** [NEW]:
   - Coordinates storage check, download, checksum verification, extraction, and bootstrapping.
   - Emits granular `InstallProgress(step, currentBytes, totalBytes, percent, message)`.
   - Supports cancellation.
6. **`future/runtime/LinuxProcessLauncher.kt`** [NEW]:
   - Builds execution command for PRoot:
     `-r <rootfsDir> -0 -b <workspaceDir>:/workspace -b <homeDir>:/home/devstation -b /dev -b /proc -b /sys -w /workspace /bin/sh`.
   - Handles fallback execution when PRoot binary is not present on the host.
7. **`future/runtime/LinuxEnvironment.kt`** [NEW]:
   - Builds environment map: `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`, `HOME=/home/devstation`, `TERM=xterm-256color`, `LANG=en_US.UTF-8`.
8. **`future/runtime/LinuxPackageManager.kt`** [NEW]:
   - Implements package management via Alpine `apk`: `update()`, `install(pkg)`, `remove(pkg)`, `listInstalled()`, `search(pkg)`.
9. **`future/runtime/DevToolsInstaller.kt`** [NEW]:
   - Orchestrates automated installation of `nodejs`, `npm`, `python3`, `py3-pip`, `git`.
   - Performs post-install verification by invoking `--version` for each tool.
10. **`future/runtime/LinuxTerminalEngine.kt`** [NEW]:
    - Implements `TerminalEngine` for Linux userspace sessions.
    - Launches interactive shell inside Linux rootfs with project workspace binding.
11. **`future/runtime/LinuxRuntimeManager.kt`** [NEW]:
    - Central coordinator for Linux runtime state, installation, diagnostics, storage tracking, reset, and uninstallation.
    - Preserves user projects upon runtime reset or uninstallation.

#### B. UI & Feature Layer (`feature/runtime/` & `feature/terminal/`)
1. **`feature/runtime/LinuxRuntimeScreen.kt`** [NEW]:
   - Runtime status overview (State, Distro, Arch, Storage, Shell, Dev Tools).
   - Interactive Installation Wizard with progress bar, step messages, and cancel button.
   - Development Tools installer card.
   - Diagnostics report view (Check Environment).
   - Reset & Uninstall actions with clear confirmation dialogs and project preservation guarantees.
2. **`feature/runtime/LinuxRuntimeViewModel.kt`** [NEW]:
   - Exposes `StateFlow<LinuxRuntimeUiState>` driven by `LinuxRuntimeManager`.
3. **`feature/terminal/TerminalScreen.kt`** [MODIFY]:
   - Adds Runtime Selector to top bar: `[ Android Shell ]` / `[ Linux Environment ]`.
   - Displays banner / prompt if Linux is selected but not yet installed, linking to installation flow.
4. **`feature/terminal/TerminalViewModel.kt`** [MODIFY]:
   - Extends session creation to support `RuntimeType.ANDROID` and `RuntimeType.LINUX`.
   - Links to `LinuxTerminalEngine` when Linux session is active.
5. **`feature/storage/StorageScreen.kt`** & **`StorageViewModel.kt`** [MODIFY]:
   - Displays Linux breakdown: Rootfs, HOME, Packages/Downloads alongside DevStation Projects.
6. **`core/filesystem/StorageStatsCalculator.kt`** [MODIFY]:
   - Adds calculations for `linuxRootfsBytes`, `linuxHomeBytes`, `linuxTotalBytes`.

#### C. Navigation & Dependency Injection
1. **`navigation/Screen.kt`** & **`navigation/DevStationNavGraph.kt`** [MODIFY]:
   - Register `Screen.LinuxRuntime("runtime")` destination.
2. **`core/di/AppContainer.kt`** [MODIFY]:
   - Expose `linuxRuntimeManager` singleton.

---

### 4. Storage Architecture

```
<context.getExternalFilesDir(null) or filesDir>/
├── projects/                        <-- User DevStation Projects (PRESERVED ALWAYS)
│   └── my-project/
│       └── .devstation/
└── linux/                           <-- Linux Userspace Root
    ├── rootfs/                      <-- Extracted Alpine Linux root filesystem
    │   ├── bin/
    │   ├── etc/
    │   ├── lib/
    │   ├── usr/
    │   └── ...
    ├── home/
    │   └── devstation/              <-- Persistent Linux HOME (survives restarts)
    │       ├── .profile
    │       └── .config/
    ├── downloads/                   <-- Download cache for rootfs tarball
    └── metadata/                    <-- runtime.json, installer.log, diagnostics.json
```
- **Reset Linux**: Deletes `rootfs/` and reinitializes, but prompts whether to keep `home/`. **Never touches `projects/`**.
- **Uninstall Linux**: Deletes `linux/` entirely. **Never touches `projects/`**.

---

### 5. Security & Isolation Model

1. **Unprivileged Execution**:
   - Operates with standard unrooted Android UID (`u0_a...`).
   - Root in Linux userspace is a PRoot-emulated UID (`uid=0`), which does NOT give Android kernel root privileges.
2. **Path Traversal & Archive Security**:
   - `TarExtractor` sanitizes every entry path.
   - Prevents directory traversal attacks (`../../`), absolute path escapes, and malicious symlinks pointing outside `rootfsDir`.
3. **Storage Isolation**:
   - App files directory is private to DevStation.
   - Android Keystore credentials and sensitive tokens are never exposed or passed into Linux environment variables.

---

### 6. PTY Support Investigation & Strategy

- Standard Android `ProcessBuilder` uses standard I/O pipe pairs (`InputStream`/`OutputStream`).
- True Unix PTY requires `/dev/ptmx` and native `openpty` ioctl system calls.
- Phase 3 will:
  1. Investigate and probe `/dev/ptmx` availability and terminal window sizing ioctl (`TIOCSWINSZ`).
  2. Implement an honest `PtySupport` abstraction that accurately detects whether real PTY is available.
  3. If full PTY native compilation is not available in unrooted Android userland, maintain the robust Pipe-Bridge fallback and clearly document in the UI: `"Full Unix PTY is not available — using Pipe-Bridge fallback"`.
  4. Never fake PTY support.

---

### 7. Testing Strategy

1. **Unit Tests**:
   - `CpuArchitectureDetectorTest`: Validates ABI mapping.
   - `RootfsManifestTest`: Validates manifest checksums and architecture selection.
   - `TarExtractorTest`:
     - Path traversal attack detection (rejects `../` and absolute paths).
     - Safe extraction of directories, regular files, and permitted symlinks.
   - `LinuxEnvironmentTest`: Validates environment variable maps, HOME, and PATH construction.
   - `LinuxPackageManagerTest`: Validates `apk` command line construction and package parsing.
   - `LinuxRuntimeManagerTest`: Validates state machine transitions and safe uninstall preserving projects.
2. **Integration / Verification**:
   - Compilation: `compileDebugKotlin`
   - Unit Tests: `testDebugUnitTest`
   - APK Packaging: `assembleDebug`
   - Output Verification: APK existence and file size.
