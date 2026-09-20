package com.devstation.android.feature.editor

import com.devstation.android.feature.editor.model.EditorLanguage
import com.devstation.android.feature.editor.service.EditorColorScheme
import com.devstation.android.feature.editor.service.RegexSyntaxHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    private val highlighter = RegexSyntaxHighlighter()
    private val colorScheme = EditorColorScheme.Dark

    @Test
    fun `highlights kotlin keywords, strings and comments`() {
        val code = """
            package com.devstation
            // This is a comment
            fun main() {
                val greeting = "Hello, DevStation"
                val count = 42
            }
        """.trimIndent()

        val annotated = highlighter.highlight(code, EditorLanguage.KOTLIN, colorScheme)
        assertEquals(code, annotated.text)

        // Verify span styles were added
        assertTrue("Expected span styles to be applied for Kotlin syntax", annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun `highlights python functions, strings and hash comments`() {
        val code = """
            # Python script
            def calculate_total(price, tax):
                message = 'Total is:'
                return price + tax
        """.trimIndent()

        val annotated = highlighter.highlight(code, EditorLanguage.PYTHON, colorScheme)
        assertEquals(code, annotated.text)
        assertTrue("Expected span styles to be applied for Python syntax", annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun `plain text returns without complex token spans`() {
        val plainText = "This is simply plain text with no syntax."
        val annotated = highlighter.highlight(plainText, EditorLanguage.TEXT, colorScheme)
        assertEquals(plainText, annotated.text)
        assertEquals(1, annotated.spanStyles.size) // Only base default color style
    }
}
