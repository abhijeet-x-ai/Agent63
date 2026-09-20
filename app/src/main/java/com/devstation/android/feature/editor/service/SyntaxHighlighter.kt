package com.devstation.android.feature.editor.service

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.devstation.android.feature.editor.model.EditorLanguage
import java.util.regex.Pattern

interface SyntaxHighlighter {
    fun highlight(code: String, language: EditorLanguage, colorScheme: EditorColorScheme): AnnotatedString
}

class RegexSyntaxHighlighter : SyntaxHighlighter {

    override fun highlight(code: String, language: EditorLanguage, colorScheme: EditorColorScheme): AnnotatedString {
        if (code.isEmpty() || language == EditorLanguage.TEXT) {
            return buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = colorScheme.text), 0, code.length)
            }
        }

        val builder = AnnotatedString.Builder(code)
        // Default text color
        builder.addStyle(SpanStyle(color = colorScheme.text), 0, code.length)

        val rules = getRulesForLanguage(language)

        // 1. Comments first (so they override keywords/numbers inside comments)
        rules.commentPatterns.forEach { pattern ->
            val matcher = pattern.matcher(code)
            while (matcher.find()) {
                builder.addStyle(
                    SpanStyle(color = colorScheme.comment, fontStyle = FontStyle.Italic),
                    matcher.start(),
                    matcher.end()
                )
            }
        }

        // 2. String literals
        rules.stringPatterns.forEach { pattern ->
            val matcher = pattern.matcher(code)
            while (matcher.find()) {
                builder.addStyle(
                    SpanStyle(color = colorScheme.string),
                    matcher.start(),
                    matcher.end()
                )
            }
        }

        // 3. Keywords
        if (rules.keywordPattern != null) {
            val matcher = rules.keywordPattern.matcher(code)
            while (matcher.find()) {
                builder.addStyle(
                    SpanStyle(color = colorScheme.keyword, fontWeight = FontWeight.Bold),
                    matcher.start(),
                    matcher.end()
                )
            }
        }

        // 4. Numbers
        val numMatcher = NUMBER_PATTERN.matcher(code)
        while (numMatcher.find()) {
            builder.addStyle(
                SpanStyle(color = colorScheme.number),
                numMatcher.start(),
                numMatcher.end()
            )
        }

        // 5. Types / Classes (Capitalized identifiers)
        val typeMatcher = TYPE_PATTERN.matcher(code)
        while (typeMatcher.find()) {
            builder.addStyle(
                SpanStyle(color = colorScheme.type),
                typeMatcher.start(),
                typeMatcher.end()
            )
        }

        // 6. Annotations / Decorators (@Annotation)
        val annotMatcher = ANNOTATION_PATTERN.matcher(code)
        while (annotMatcher.find()) {
            builder.addStyle(
                SpanStyle(color = colorScheme.annotation),
                annotMatcher.start(),
                annotMatcher.end()
            )
        }

        return builder.toAnnotatedString()
    }

    private data class LanguageRules(
        val keywordPattern: Pattern?,
        val commentPatterns: List<Pattern>,
        val stringPatterns: List<Pattern>
    )

    private fun getRulesForLanguage(lang: EditorLanguage): LanguageRules {
        val comments = mutableListOf<Pattern>()
        val strings = mutableListOf<Pattern>()

        // Default string patterns: double-quoted and single-quoted
        strings.add(DOUBLE_QUOTE_STRING)
        strings.add(SINGLE_QUOTE_STRING)

        when (lang) {
            EditorLanguage.KOTLIN, EditorLanguage.JAVA, EditorLanguage.JAVASCRIPT,
            EditorLanguage.TYPESCRIPT, EditorLanguage.C, EditorLanguage.CPP,
            EditorLanguage.RUST, EditorLanguage.GO, EditorLanguage.DART,
            EditorLanguage.SWIFT, EditorLanguage.CSS -> {
                comments.add(SLASH_SLASH_COMMENT)
                comments.add(BLOCK_COMMENT)
                if (lang == EditorLanguage.JAVASCRIPT || lang == EditorLanguage.TYPESCRIPT) {
                    strings.add(BACKTICK_STRING)
                }
            }
            EditorLanguage.PYTHON, EditorLanguage.SHELL, EditorLanguage.YAML -> {
                comments.add(HASH_COMMENT)
                if (lang == EditorLanguage.PYTHON) {
                    strings.add(TRIPLE_DOUBLE_QUOTE_STRING)
                    strings.add(TRIPLE_SINGLE_QUOTE_STRING)
                }
            }
            EditorLanguage.SQL -> {
                comments.add(DASH_DASH_COMMENT)
                comments.add(BLOCK_COMMENT)
            }
            EditorLanguage.HTML, EditorLanguage.XML -> {
                comments.add(HTML_COMMENT)
            }
            EditorLanguage.JSON, EditorLanguage.MARKDOWN, EditorLanguage.TEXT -> {
                // No special comments
            }
        }

        val keywords = KEYWORD_MAP[lang]
        val kwPattern = if (!keywords.isNullOrEmpty()) {
            val kwRegex = "\\b(${keywords.joinToString("|")})\\b"
            Pattern.compile(kwRegex, if (lang == EditorLanguage.SQL) Pattern.CASE_INSENSITIVE else 0)
        } else null

        return LanguageRules(
            keywordPattern = kwPattern,
            commentPatterns = comments,
            stringPatterns = strings
        )
    }

    companion object {
        private val DOUBLE_QUOTE_STRING = Pattern.compile("\"(\\\\.|[^\"])*\"")
        private val SINGLE_QUOTE_STRING = Pattern.compile("'(\\\\.|[^'])*'")
        private val BACKTICK_STRING = Pattern.compile("`(\\\\.|[^`])*`")
        private val TRIPLE_DOUBLE_QUOTE_STRING = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"")
        private val TRIPLE_SINGLE_QUOTE_STRING = Pattern.compile("'''[\\s\\S]*?'''")

        private val SLASH_SLASH_COMMENT = Pattern.compile("//.*")
        private val HASH_COMMENT = Pattern.compile("#.*")
        private val DASH_DASH_COMMENT = Pattern.compile("--.*")
        private val BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/")
        private val HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->")

        private val NUMBER_PATTERN = Pattern.compile("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fFL]?)\\b")
        private val TYPE_PATTERN = Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b")
        private val ANNOTATION_PATTERN = Pattern.compile("@[a-zA-Z0-9_]+")

        private val KEYWORD_MAP = mapOf(
            EditorLanguage.KOTLIN to listOf(
                "package", "import", "class", "interface", "val", "var", "fun", "return",
                "if", "else", "when", "for", "while", "try", "catch", "finally", "throw",
                "public", "private", "protected", "internal", "override", "open", "abstract",
                "data", "sealed", "object", "companion", "enum", "suspend", "inline", "tailrec",
                "operator", "infix", "reified", "crossinline", "noinline", "by", "lazy", "lateinit",
                "is", "as", "in", "null", "true", "false", "this", "super", "constructor", "init"
            ),
            EditorLanguage.JAVA to listOf(
                "package", "import", "public", "private", "protected", "class", "interface",
                "enum", "extends", "implements", "final", "static", "abstract", "synchronized",
                "volatile", "transient", "native", "void", "boolean", "int", "long", "float",
                "double", "char", "byte", "short", "if", "else", "switch", "case", "default",
                "for", "while", "do", "break", "continue", "return", "try", "catch", "finally",
                "throw", "throws", "new", "this", "super", "instanceof", "null", "true", "false"
            ),
            EditorLanguage.JAVASCRIPT to listOf(
                "function", "const", "let", "var", "return", "if", "else", "switch", "case",
                "default", "for", "while", "do", "break", "continue", "try", "catch", "finally",
                "throw", "async", "await", "import", "from", "export", "class", "extends",
                "new", "this", "super", "typeof", "instanceof", "in", "of", "delete", "void",
                "null", "undefined", "true", "false", "yield"
            ),
            EditorLanguage.TYPESCRIPT to listOf(
                "function", "const", "let", "var", "return", "if", "else", "switch", "case",
                "default", "for", "while", "do", "break", "continue", "try", "catch", "finally",
                "throw", "async", "await", "import", "from", "export", "class", "interface",
                "type", "extends", "implements", "new", "this", "super", "typeof", "instanceof",
                "in", "of", "delete", "void", "null", "undefined", "true", "false", "yield",
                "declare", "readonly", "abstract", "as", "keyof", "never", "unknown", "any",
                "string", "number", "boolean", "enum", "namespace"
            ),
            EditorLanguage.PYTHON to listOf(
                "def", "class", "return", "if", "elif", "else", "for", "while", "try",
                "except", "finally", "raise", "import", "from", "as", "with", "async",
                "await", "lambda", "pass", "break", "continue", "yield", "global",
                "nonlocal", "in", "is", "not", "and", "or", "True", "False", "None", "self"
            ),
            EditorLanguage.RUST to listOf(
                "fn", "let", "mut", "struct", "enum", "impl", "trait", "pub", "use", "mod",
                "crate", "return", "match", "if", "else", "loop", "while", "for", "in",
                "break", "continue", "as", "where", "type", "const", "static", "async",
                "await", "unsafe", "true", "false", "move", "ref", "self", "Self"
            ),
            EditorLanguage.GO to listOf(
                "package", "import", "func", "var", "const", "type", "struct", "interface",
                "return", "if", "else", "switch", "case", "default", "for", "range", "go",
                "chan", "select", "defer", "nil", "true", "false", "map", "make", "new"
            ),
            EditorLanguage.C to listOf(
                "auto", "break", "case", "char", "const", "continue", "default", "do",
                "double", "else", "enum", "extern", "float", "for", "goto", "if", "int",
                "long", "register", "return", "short", "signed", "sizeof", "static",
                "struct", "switch", "typedef", "union", "unsigned", "void", "volatile",
                "while", "NULL", "true", "false"
            ),
            EditorLanguage.CPP to listOf(
                "class", "struct", "template", "typename", "namespace", "using", "public",
                "private", "protected", "virtual", "override", "const", "static", "int",
                "char", "float", "double", "void", "bool", "return", "if", "else", "for",
                "while", "do", "switch", "case", "break", "continue", "new", "delete",
                "nullptr", "true", "false", "try", "catch", "throw", "auto", "constexpr",
                "explicit", "friend", "inline", "mutable", "noexcept", "operator"
            ),
            EditorLanguage.SQL to listOf(
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "UPDATE", "DELETE", "JOIN",
                "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "ASC",
                "DESC", "HAVING", "LIMIT", "OFFSET", "CREATE", "TABLE", "DROP", "ALTER",
                "INDEX", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "NULL", "NOT", "AND",
                "OR", "IN", "EXISTS", "BETWEEN", "LIKE", "AS", "UNION", "ALL", "VIEW",
                "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "VALUES", "SET", "DEFAULT"
            ),
            EditorLanguage.SHELL to listOf(
                "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while",
                "until", "case", "esac", "function", "return", "exit", "echo", "export",
                "source", "local", "readonly", "set", "unset", "shift", "trap", "test"
            ),
            EditorLanguage.DART to listOf(
                "abstract", "as", "assert", "async", "await", "break", "case", "catch",
                "class", "const", "continue", "default", "deferred", "do", "dynamic",
                "else", "enum", "export", "extends", "extension", "external", "factory",
                "false", "final", "finally", "for", "get", "if", "implements", "import",
                "in", "interface", "is", "late", "library", "mixin", "new", "null",
                "part", "required", "rethrow", "return", "set", "show", "static", "super",
                "switch", "sync", "this", "throw", "true", "try", "typedef", "var", "void",
                "while", "with", "yield"
            ),
            EditorLanguage.SWIFT to listOf(
                "associatedtype", "class", "deinit", "enum", "extension", "fileprivate",
                "func", "import", "init", "inout", "internal", "let", "open", "operator",
                "private", "protocol", "public", "rethrows", "static", "struct", "subscript",
                "typealias", "var", "break", "case", "continue", "default", "defer", "do",
                "else", "fallthrough", "for", "guard", "if", "in", "repeat", "return",
                "switch", "where", "while", "as", "false", "is", "nil", "self", "Self",
                "super", "true", "try", "throws", "await", "async"
            )
        )
    }
}
