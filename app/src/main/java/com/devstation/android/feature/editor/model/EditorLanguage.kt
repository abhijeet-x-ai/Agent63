package com.devstation.android.feature.editor.model

enum class EditorLanguage(
    val displayName: String,
    val extensions: List<String>,
    val lineCommentPrefix: String? = null,
    val blockCommentStart: String? = null,
    val blockCommentEnd: String? = null
) {
    KOTLIN("Kotlin", listOf("kt", "kts"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    JAVA("Java", listOf("java"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    JAVASCRIPT("JavaScript", listOf("js", "mjs", "cjs", "jsx"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    TYPESCRIPT("TypeScript", listOf("ts", "mts", "cts", "tsx"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    JSON("JSON", listOf("json"), lineCommentPrefix = null),
    HTML("HTML", listOf("html", "htm"), lineCommentPrefix = null, blockCommentStart = "<!--", blockCommentEnd = "-->"),
    CSS("CSS", listOf("css", "scss", "sass", "less"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    XML("XML", listOf("xml", "svg"), lineCommentPrefix = null, blockCommentStart = "<!--", blockCommentEnd = "-->"),
    MARKDOWN("Markdown", listOf("md", "markdown"), lineCommentPrefix = null),
    PYTHON("Python", listOf("py", "pyw"), lineCommentPrefix = "#"),
    SHELL("Shell", listOf("sh", "bash", "zsh"), lineCommentPrefix = "#"),
    YAML("YAML", listOf("yaml", "yml"), lineCommentPrefix = "#"),
    SQL("SQL", listOf("sql"), lineCommentPrefix = "--", blockCommentStart = "/*", blockCommentEnd = "*/"),
    C("C", listOf("c", "h"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    CPP("C++", listOf("cpp", "cxx", "cc", "hpp", "hxx"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    RUST("Rust", listOf("rs"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    GO("Go", listOf("go"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    DART("Dart", listOf("dart"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    SWIFT("Swift", listOf("swift"), lineCommentPrefix = "//", blockCommentStart = "/*", blockCommentEnd = "*/"),
    TEXT("Plain Text", listOf("txt", "log", "conf", "env", "properties", "gitignore"), lineCommentPrefix = null);

    companion object {
        fun fromExtension(ext: String): EditorLanguage {
            val normalized = ext.lowercase().trimStart('.')
            return entries.firstOrNull { it.extensions.contains(normalized) } ?: TEXT
        }

        fun fromFileName(fileName: String): EditorLanguage {
            val lower = fileName.lowercase()
            return when {
                lower == "dockerfile" -> SHELL
                lower == "makefile" -> SHELL
                lower.startsWith(".env") -> TEXT
                lower == ".gitignore" -> TEXT
                else -> {
                    val dotIndex = lower.lastIndexOf('.')
                    if (dotIndex != -1 && dotIndex < lower.length - 1) {
                        fromExtension(lower.substring(dotIndex + 1))
                    } else {
                        TEXT
                    }
                }
            }
        }
    }
}
