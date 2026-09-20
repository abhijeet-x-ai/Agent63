# DevStation (Agent63) 📱⚡💻

> **Turn an Android Phone into an Autonomous, Production-Grade AI Development Workstation.**

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.02.00-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Material 3](https://img.shields.io/badge/Material%203-Expressive%20Design-6750A4)](https://m3.material.io)
[![Room](https://img.shields.io/badge/Database-Room%202.6.1-F57C00?logo=sqlite&logoColor=white)](https://developer.android.com/training/data-storage/room)
[![Linux Runtime](https://img.shields.io/badge/Userspace-Alpine%20Linux%20PRoot-0D597F?logo=alpinelinux&logoColor=white)](https://alpinelinux.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

---

## 🌟 Executive Summary

**DevStation** is an advanced native Android IDE and development workstation built from scratch using clean modern Android architecture, Jetpack Compose, and Kotlin Coroutines. It delivers a full development environment directly on a mobile device:

- **Phase 1: Android Architecture & UI Foundation** — Material 3 responsive design, Room persistence, filesystem sandboxing, and Android Keystore security.
- **Phase 2: Local Terminal & Process Engine** — High-performance shell process execution with streaming I/O, ANSI 16/256-color rendering, multi-session tabs, and mobile developer keyboard accessory.
- **Phase 3: Linux Userspace Runtime** — Alpine Linux rootfs distribution, PRoot sandboxing without root access, `apk` package manager, and one-click dev toolchains (Node.js, Python 3, Git).
- **Phase 4: Professional Mobile Code Editor** — Tabbed multi-file editor, lexical syntax highlighting across 8+ languages, search & replace, bracket matching, undo/redo history engine, and atomic save safety.

---

## 📐 System Architecture

```
                                  DEVSTATION SYSTEM ARCHITECTURE
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │                                JETPACK COMPOSE UI LAYER                                │
  │  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────┐  │
  │  │  Home Screen  │ │Projects Screen│ │ Files Screen  │ │Terminal Screen│ │Code Editor│  │
  │  └───────┬───────┘ └───────┬───────┘ └───────┬───────┘ └───────┬───────┘ └─────┬─────┘  │
  │          │                 │                 │                 │               │        │
  │  ┌───────▼─────────────────▼─────────────────▼─────────────────▼───────────────▼─────┐  │
  │  │                            STATEFLOW VIEWMODELS LAYER                             │  │
  └──┴──────────────────────────────────────────┬────────────────────────────────────────┴──┘
                                                │
  ┌─────────────────────────────────────────────▼──────────────────────────────────────────┐
  │                                    CORE ENGINE SERVICES                                │
  │  ┌──────────────────────┐ ┌──────────────────────┐ ┌────────────────────────────────┐  │
  │  │   TerminalManager    │ │  LinuxRuntimeManager │ │       EditorFileManager        │  │
  │  │  - Process streaming │ │  - Alpine Rootfs     │ │  - Atomic file saves           │  │
  │  │  - ANSI Parser       │ │  - PRoot launcher    │ │  - UTF-8 / Encoding detector   │  │
  │  │  - 5000-line buffer  │ │  - DevTools recipes  │ │  - Syntax highlighter          │  │
  │  └──────────┬───────────┘ └──────────┬───────────┘ └───────────────┬────────────────┘  │
  └─────────────┼────────────────────────┼─────────────────────────────┼────────────────────┘
                │                        │                             │
  ┌─────────────▼────────────────────────▼─────────────────────────────▼────────────────────┐
  │                                DATA & REPOSITORY LAYER                                 │
  │  ┌───────────────────┐  ┌───────────────────┐  ┌─────────────────┐  ┌────────────────┐ │
  │  │ ProjectRepository │  │  ConversationRepo │  │  SettingsRepo   │  │  SecureStore   │ │
  │  └─────────┬─────────┘  └─────────┬─────────┘  └────────┬────────┘  └───────┬────────┘ │
  │            │                      │                     │                   │          │
  │  ┌─────────▼──────────────────────▼─────────────────────▼────────┐  ┌───────▼────────┐ │
  │  │              Room SQLite Database (DevStationDb)              │  │ Android KeyStore│ │
  │  └───────────────────────────────────────────────────────────────┘  └────────────────┘ │
  └─────────────────────────────────────────────┬──────────────────────────────────────────┘
                                                │
  ┌─────────────────────────────────────────────▼──────────────────────────────────────────┐
  │                                    OS SANDBOX & KERNEL                                 │
  │  /data/data/com.devstation.android/files/   •   POSIX PTY / Pipes   •   ProcessBuilder │
  └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Key Modules & Capabilities

### 1. Modern Architecture & Navigation
- **Single Activity Architecture** hosted in `MainActivity.kt` with edge-to-edge Compose rendering.
- **Adaptive Scaffolding (`DevStationResponsiveScaffold`)**:
  - Automatically switches between **Bottom Navigation Bar** on mobile portrait and **Navigation Rail** on landscape/tablets.
  - Live top workstation context bar displaying active project, runtime environment, and AI agent status.
- **Manual Dependency Injection**: Robust `AppContainer` providing singletons and lazy-initialized repository, terminal, and runtime instances.
- **Room Database**: 5 optimized SQLite tables (`projects`, `project_files`, `conversations`, `messages`, `app_settings`) with reactive Flow queries and schema migrations.
- **Keystore Security**: AES-256-GCM authenticated encryption through Android KeyStore provider for developer API keys and credentials.

### 2. Interactive Terminal & Process Engine
- **LocalProcessTerminalEngine**: Non-blocking `ProcessBuilder` shell execution with real-time `stdin`, `stdout`, and `stderr` Kotlin Coroutine flows.
- **ANSI Parsing**: Custom `AnsiParser` supporting standard 16 and 256 colors, text attributes (bold, underline, inverted), and cursor positioning mapped to Jetpack Compose `AnnotatedString`.
- **Multi-Session Management**: Unlimited concurrent background terminal sessions with independent working directories, exit code monitors, and session switcher tabs.
- **Bounded Buffer & Security Filter**: 5,000-line ring buffer per session with automatic credential and authorization token masking (`SECRET_FILTER_REGEX`).
- **Mobile Terminal Keyboard**: Ergonomic quick-touch keyboard bar featuring `ESC`, `TAB`, `CTRL`, `ALT`, cursor arrows (`↑`, `↓`, `←`, `→`), and pipe (`|`).

### 3. Alpine Linux Userspace Runtime
- **Zero-Root Userspace**: Embedded PRoot runtime allowing unprivileged execution of standard Linux binary ELF executables.
- **CPU Architecture Detection**: Native detection for ARM64 (`aarch64`), x86_64 (`amd64`), and 32-bit platforms with automatic fallback.
- **Automated Rootfs Extraction**: Streaming `TarExtractor` handling GNU and POSIX tar formats with gzip decompression, symlink creation, and permission restoration.
- **Package Manager Integration**: Full `LinuxPackageManager` wrapper over Alpine `apk` for package index updates, package search, and installation.
- **Dev Toolchains**: Pre-built recipes in `DevToolsInstaller` for Python 3 (`python3`, `py3-pip`), Node.js (`nodejs`, `npm`), Git (`git`), and build tools.

### 4. Professional Code Editor System
- **Tabbed Multi-File Editing**: Concurrent file tabs with dirty state flags (`●`), tab reordering, and close confirmations.
- **Multi-Language Lexical Highlighting**: Regex-based tokenization supporting Kotlin, Python, JavaScript, TypeScript, JSON, YAML, Bash, and Markdown.
- **Safe File Operations**:
  - Atomic saves: writes to temporary hidden files (`.filename.tmp`) before atomic replacement.
  - Path traversal and symlink escape defenses.
  - Character encoding detector (UTF-8, ISO-8859-1, ASCII) with CRLF/LF normalization.
- **History Engine**: Bounded undo/redo stack recording document states and cursor positions.
- **Project Search & Replace**: Regular expressions, case sensitivity, word boundary matches, and directory-wide search.
- **Touch Ergonomics**: Monospace line numbers, bracket match highlights, indentation guidelines, and mobile programming accessory keys.

---

## 📂 Repository Structure

```
Agent63/
├── .github/                         # GitHub Actions & PR workflows
├── app/
│   ├── build.gradle.kts             # Module build configurations & dependencies
│   ├── proguard-rules.pro           # R8 / ProGuard optimization rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  # Permissions and application declaration
│       │   ├── java/com/devstation/android/
│       │   │   ├── DevStationApp.kt # Application class & container init
│       │   │   ├── MainActivity.kt  # Root activity & Compose entrypoint
│       │   │   ├── core/            # Foundation architecture
│       │   │   │   ├── common/      # Dispatchers, format utils, results
│       │   │   │   ├── database/    # Room DB, DAOs, Entities, Converters
│       │   │   │   ├── di/          # AppContainer service locator
│       │   │   │   ├── filesystem/  # Sandbox manager, storage calculator
│       │   │   │   ├── model/       # Domain data models & settings
│       │   │   │   ├── repository/  # Projects, conversations, settings repos
│       │   │   │   ├── security/    # Keystore AES-GCM credential store
│       │   │   │   └── ui/          # Themes, typography, custom icons, scaffold
│       │   │   ├── feature/         # Feature UI & ViewModels
│       │   │   │   ├── conversations/ # AI Session lists & message timeline
│       │   │   │   ├── editor/      # Code editor UI, ViewModels, services, models
│       │   │   │   ├── files/       # Tree file browser & preview dialogs
│       │   │   │   ├── home/        # Dashboard, quick tools & recent projects
│       │   │   │   ├── projects/    # Project manager, settings, search & filter
│       │   │   │   ├── runtime/     # Linux userspace status & package manager UI
│       │   │   │   ├── settings/    # Theme switcher, paths, keystore controls
│       │   │   │   ├── storage/     # Storage space breakdown & cache clearing
│       │   │   │   └── terminal/    # Terminal screen, keyboard bar & tabs
│       │   │   ├── future/          # Extension engines & subsystem contracts
│       │   │   │   ├── runtime/     # Linux environment, PRoot launcher, packages
│       │   │   │   └── terminal/    # Terminal engine, process launcher, ANSI parser
│       │   │   └── navigation/      # DevStationNavGraph & typed Screen routes
│       │   └── res/                 # App strings, themes, and drawables
│       └── test/                    # Comprehensive unit tests suite (18 test files)
├── build.gradle.kts                 # Root Gradle build script
├── gradle.properties                # Build JVM options & AndroidX properties
├── gradlew / gradlew.bat            # Gradle wrapper executable
└── settings.gradle.kts              # Repository plugins & module settings
```

---

## 🧪 Verification & Test Suite

The codebase includes **18 comprehensive unit test suites** verifying core functionality:

| Component | Test Suite File | Coverage Scope |
|---|---|---|
| **Filesystem** | `ProjectFileSystemManagerTest.kt` | Sandboxing, path security, recursive size calculation, quota checks |
| **Common Utils** | `FormatUtilsTest.kt` | Byte formatting, duration formatting, relative time formatting |
| **Terminal Engine** | `TerminalSessionTest.kt` | Session lifecycle, 5,000-line buffer bounds, secret filtering |
| **Terminal Engine** | `TerminalManagerTest.kt` | Concurrent sessions, active session selection, session termination |
| **ANSI Parser** | `AnsiParserTest.kt` | 16-color ANSI, 256-color ANSI, bold, underline, stripped text |
| **Shell Detection** | `ShellDetectorTest.kt` | System shell discovery and fallback selection |
| **CPU Detection** | `CpuArchitectureDetectorTest.kt` | ARM64, x86_64, 32-bit architecture identification |
| **Tar Extraction** | `TarExtractorTest.kt` | Tar archive unpacking, symlinks, directory permissions |
| **Linux Launcher** | `LinuxProcessLauncherTest.kt` | PRoot command string assembly, mount arguments |
| **Linux Runtime** | `LinuxRuntimeManagerTest.kt` | Installation state machine transitions, package status |
| **Linux Manifest** | `RootfsManifestTest.kt` | Architecture URLs, SHA256 checksum registry |
| **Linux Env** | `LinuxEnvironmentTest.kt` | Environment variable maps, path initialization |
| **Editor File** | `EditorFileManagerTest.kt` | Atomic write pattern, encoding detection, backup handling |
| **Editor History** | `EditorHistoryTest.kt` | Undo/Redo stack, batch operations, cursor restoration |
| **Language Detect** | `EditorLanguageDetectorTest.kt` | Extension matching for Kotlin, Python, JS, TS, Bash, etc. |
| **Search Engine** | `ProjectSearchEngineTest.kt` | Regex search, case sensitive, whole word, file filtering |
| **Syntax Highlighting** | `SyntaxHighlighterTest.kt` | Keyword, string, comment, number span generation |
| **Encodings** | `UnicodeEncodingTest.kt` | Multi-byte UTF-8, emojis, Windows CRLF to UNIX LF conversions |

---

## 🛠️ Building & Running

### Prerequisites
- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK** 17 or higher
- **Android SDK** API Level 34 (Android 14) with Build Tools 34.0.0

### Command Line Build
```bash
# Clone the repository
git clone https://github.com/abhijeet-x-ai/Agent63.git
cd Agent63

# Build debug APK
./gradlew assembleDebug

# Run unit test suite
./gradlew testDebugUnitTest
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).