# DEVSTATION — PHASE 4 IMPLEMENTATION PLAN
## Professional Code Editor + File Editing System

**Author**: DevStation Senior Mobile IDE Architect  
**Target Milestone**: Phase 4  
**Date**: September 20, 2026  

---

## 1. Current Architecture Review

DevStation currently consists of three fully verified phases:
1. **Phase 1 Foundation**:
   - Clean Architecture: `core/model`, `core/database`, `core/repository`, `core/filesystem`, `core/security`, `core/ui`.
   - `ProjectFileSystemManager`: Manages `<filesDir>/projects` sandbox, provides directory creation, file creation, listing, renaming, recursive deletion, and basic file sanitization.
   - Room Database: Manages `ProjectEntity`, `ConversationEntity`, `MessageEntity`, `AppSettingsEntity`.
   - Responsive UI: `ResponsiveScaffold` supporting portrait phone navigation bar and landscape navigation rail.
2. **Phase 2 Terminal & Process Management**:
   - `TerminalEngine`, `TerminalSession`, `TerminalManager`, `AnsiParser`, `ShellDetector`.
   - Interactive stdin/stdout/stderr streaming with bounded 5,000-line memory circular buffer, secret redaction, and mobile keyboard bar.
3. **Phase 3 Linux Runtime**:
   - Alpine Linux 3.19.1 minirootfs (`aarch64` and `x86_64`) running unprivileged via PRoot.
   - Node.js, npm, Python 3, pip, Git automation via Alpine `apk`.
   - Project workspace bind mount (`/projects` -> `/workspace`).
   - Dual-runtime terminal selector and dedicated runtime diagnostics/management screen.

---

## 2. Phase 4 Editor Architecture

The Code Editor will be located in `feature/editor/` and `core/database/` with a modular, decoupled architecture:

```
app/src/main/java/com/devstation/android/
├── core/
│   ├── database/
│   │   ├── Entities.kt                  # Added: RecentFileEntity, EditorSettingsEntity
│   │   ├── Daos.kt                      # Added: RecentFileDao, EditorSettingsDao
│   │   └── DevStationDatabase.kt        # Updated: Entities list + version increment
│   └── di/
│       └── AppContainer.kt              # Exposed: editorFileManager, editorSettingsRepository
└── feature/
    ├── editor/
    │   ├── model/
    │   │   ├── EditorDocument.kt        # In-memory document with encoding, line ending, hash
    │   │   ├── EditorTab.kt             # Tab state (file path, modified, cursor, scroll)
    │   │   ├── EditorCursor.kt          # Line/Col position, selection range
    │   │   ├── EditorHistory.kt         # Undo/Redo stack with snapshot throttling
    │   │   ├── EditorSearch.kt          # In-file find/replace & project-wide search models
    │   │   └── EditorSettings.kt        # Font size, tab size, line numbers, word wrap, etc.
    │   ├── service/
    │   │   ├── EditorFileManager.kt     # Atomic save, recovery snapshots, binary/large file checks
    │   │   ├── EditorLanguageDetector.kt# Extension/filename to EditorLanguage mapping (20+ langs)
    │   │   ├── SyntaxHighlighter.kt     # Fast regex/token syntax coloring into AnnotatedString
    │   │   ├── EditorColorScheme.kt     # Dark & Light syntax color schemes
    │   │   ├── ProjectSearchEngine.kt   # Async project-wide recursive text search
    │   │   └── LanguageService.kt       # Decoupled interface for future Phase LSP integration
    │   ├── ui/
    │   │   ├── EditorScreen.kt          # Main responsive editor scaffold
    │   │   ├── EditorTabBar.kt          # Scrollable tab strip with modified indicator and close button
    │   │   ├── CodeEditorView.kt        # Core editor with synchronized line numbers & syntax highlighting
    │   │   ├── EditorToolbar.kt         # Mobile quick actions (undo, redo, find, indent, comment) & symbol bar
    │   │   ├── EditorSearchSheet.kt     # In-file Find & Replace overlay with match counter
    │   │   ├── ProjectSearchDialog.kt   # Project-wide search results modal
    │   │   ├── EditorDiagnosticsDialog.kt # File encoding, line endings, size, cursor stats
    │   │   ├── UnsavedChangesDialog.kt  # Save / Discard / Cancel dialog
    │   │   └── ExternalChangeDialog.kt  # Reload / Keep / Compare dialog
    │   └── EditorViewModel.kt           # StateFlow-driven coordinator for tabs, files, undo/redo
    └── files/
        └── FilesScreen.kt               # Enhanced with "Open in Editor" and developer file tree icons
```

---

## 3. File Model & Source of Truth

- **Single Source of Truth**: The host filesystem at `<filesDir>/projects/<projectId>/` is the sole source of truth.
- **`EditorDocument`**:
  - `filePath: String` (canonical absolute path)
  - `content: String` (current working text buffer)
  - `encoding: Charset` (UTF-8 by default; BOM detected and preserved)
  - `lineEnding: LineEnding` (LF, CRLF, CR detected and preserved)
  - `fileSizeBytes: Long`
  - `lastModified: Long` (host timestamp at load time)
  - `contentHash: String` (SHA-256 of persisted disk content for external change detection)
  - `isBinary: Boolean`
- **File Safety**:
  - All file access validates canonical path boundaries against `projectRoot.canonicalFile`.
  - Path traversal attempts (`..`, symlinks outside project, absolute external paths) are rejected with `SecurityException`.

---

## 4. Editor State Model

- **`EditorUiState`**:
  - `tabs: List<EditorTab>`
  - `activeTabId: String?`
  - `settings: EditorSettings`
  - `searchState: EditorSearchState`
  - `projectSearchState: ProjectSearchState`
  - `diagnostics: EditorDiagnostics?`
  - `pendingCloseTab: EditorTab?` (triggers UnsavedChangesDialog)
  - `externalChangeNotice: ExternalChangeNotice?` (triggers ExternalChangeDialog)
  - `largeFileNotice: LargeFileNotice?` (triggers LargeFileWarningDialog)
  - `recentFiles: List<RecentFileItem>`
  - `userMessage: String?`

---

## 5. Syntax Highlighting Strategy

- **Architectural Decoupling**: Highlighting is computed via a pure `SyntaxHighlighter` interface:
  ```kotlin
  interface SyntaxHighlighter {
      fun highlight(code: String, language: EditorLanguage, colorScheme: EditorColorScheme): AnnotatedString
  }
  ```
- **Supported Languages (Minimum 20)**:
  Kotlin, Java, JavaScript, TypeScript, JSON, HTML, CSS, XML, Markdown, Python, Shell/Bash, YAML, SQL, C, C++, Rust, Go, Dart, Swift, Text/Properties/Dockerfile.
- **Token Classifications**:
  - Keywords (`class`, `fun`, `val`, `var`, `if`, `return`, `import`, etc.)
  - String literals (single-line, multi-line, template strings)
  - Numeric literals (integers, floats, hex, binaries)
  - Comments (line comments `//`, `#`, `--`, block comments `/* */`)
  - Types / Annotations / Decorators
  - Punctuation & Operators
- **Theming**:
  - Dark Theme: High contrast palette aligning with DevStation `DarkBg`, `BlueAccent`, `EmeraldAccent`, `AmberAccent`, `RedAccent`.
  - Light Theme: Clean studio palette aligning with `LightBg`, `LightBlueAccent`.
- **Future LSP Compatibility**: Future phases can provide an `LspSyntaxHighlighter` implementing the exact same interface without modifying the editor UI.

---

## 6. Search Architecture

- **In-File Find & Replace**:
  - Search options: `caseSensitive`, `wholeWord`, `isRegex`.
  - Background calculation of match ranges: `List<IntRange>`.
  - Real-time match navigation: Next (`↓`), Previous (`↑`), and current index (`3 of 15`).
  - Replace single occurrence and Replace All (with confirmation dialog stating match count).
- **Project-Wide Search (`ProjectSearchEngine`)**:
  - Background coroutine scanning the project filesystem recursively.
  - Automatically excludes: `.git/`, `node_modules/`, `build/`, `.devstation/`, and binary files.
  - Returns `List<ProjectSearchResult>` with `filePath`, `relativePath`, `lineNumber`, and line snippet.
  - Tapping a search result opens the file in a new or existing editor tab and jumps directly to that line.

---

## 7. Undo / Redo Strategy

- **`EditorHistory`**:
  - Dual stack: `undoStack: ArrayDeque<HistorySnapshot>` and `redoStack: ArrayDeque<HistorySnapshot>`.
  - Max history depth: 100 snapshots per tab to limit memory.
  - Snapshot properties: `content: String`, `cursorPosition: Int`, `timestamp: Long`.
  - **Debounced Capture**: Consecutive typing captures a snapshot only when:
    - Time since last snapshot > 800ms
    - Character typed is a newline, space, or punctuation symbol (word boundary)
    - Cut, paste, replace, or indent action occurs.

---

## 8. Autosave, Recovery & Atomic Save Strategy

- **Atomic File Saving**:
  1. Write updated content to `<filePath>.tmp.<uuid>`.
  2. Flush file descriptor to disk.
  3. Atomically rename/replace target file (`StandardCopyOption.ATOMIC_MOVE` or fallback rename).
  4. Preserves line ending (LF/CRLF) and character encoding (UTF-8, UTF-8 BOM).
- **Autosave & Crash Recovery**:
  - Unsaved modified tabs periodically write a recovery snapshot to `<projectRoot>/.devstation/recovery/<fileHash>.recovery`.
  - Recovery file contains: original canonical path, timestamp, and unsaved content.
  - On opening a file: if a newer recovery snapshot exists, prompt: "Unsaved changes recovered. Restore or Discard?".
  - Upon clean save or clean tab close, the recovery snapshot is deleted.

---

## 9. Large File & Binary File Strategy

- **File Size Thresholds**:
  - `< 5 MB`: Normal full-featured code editor with syntax highlighting and line numbers.
  - `5 MB – 20 MB`: Optimized mode (prompts user; disables expensive regex syntax highlighting for fluid typing).
  - `> 20 MB`: Large file safety barrier (prevents accidental OOM; offers read-only preview mode or explicit user override).
- **Binary File Detection**:
  - Checks known binary extensions (`png`, `jpg`, `apk`, `zip`, `pdf`, `mp4`, `class`, `so`, etc.).
  - Content inspection: Reads the first 8,192 bytes; if null bytes (`0x00`) or non-text control characters are detected, marks file as binary.
  - Binary files display a dedicated placeholder card with file size, last modified date, and "Open with System App" action.

---

## 10. External File Modification Detection

- Terminal (Phase 2) and Linux `/workspace` (Phase 3) modify files directly on disk.
- When the Editor gains focus or checks a tab:
  - Compares disk file `lastModified` and content SHA-256 against `document.lastModified` and `document.contentHash`.
  - If modified on disk:
    - If document has no unsaved changes: silently reloads to show latest content.
    - If document has unsaved user edits: shows `ExternalChangeDialog` offering:
      1. **Reload from Disk** (discards local edits)
      2. **Keep Editor Version** (overwrites disk on next save)
      3. **Compare** (side-by-side or view external snippet)

---

## 11. Testing Strategy

1. **Unit Tests (Target: 20+ new tests)**:
   - `EditorLanguageDetectorTest`: Verifies mapping of 20+ extensions and filenames.
   - `SyntaxHighlighterTest`: Verifies keyword/comment/string tokenization for Kotlin, Python, JS, etc.
   - `EditorHistoryTest`: Verifies undo, redo, stack capping, and snapshot throttling.
   - `EditorFileManagerTest`: Verifies atomic save, path traversal protection, binary detection, encoding preservation (UTF-8, BOM), and line ending preservation (LF, CRLF).
   - `ProjectSearchEngineTest`: Verifies project-wide search, directory exclusions (`.git`, `build`), and line match extraction.
   - `UnicodeEncodingTest`: Tests multilingual support with Bengali (`বাংলা`), Hindi (`हिन्दी`), Chinese (`中文`), Japanese (`日本語`), Arabic (`العربية`), and emoji.
2. **Regression Testing**:
   - Verify all 34 Phase 1, Phase 2, and Phase 3 unit tests continue to pass (Total: 54+ tests).
   - Verify `compileDebugKotlin` and `assembleDebug` pass.

---

## 12. Future LSP Compatibility (Phase 5+)

- Introduces a clean `LanguageService` interface in `feature/editor/service/LanguageService.kt`:
  ```kotlin
  interface LanguageService {
      suspend fun getCompletions(filePath: String, line: Int, col: Int): List<CompletionItem>
      suspend fun getDiagnostics(filePath: String): List<DiagnosticItem>
      suspend fun getHoverInfo(filePath: String, line: Int, col: Int): String?
  }
  ```
- DevStation will ship with a `NoOpLanguageService` for Phase 4.
- This preserves clear architectural boundaries for Phase 5+ while ensuring the editor UI and document models are 100% prepared for language server protocol integration without architectural rewrites.
