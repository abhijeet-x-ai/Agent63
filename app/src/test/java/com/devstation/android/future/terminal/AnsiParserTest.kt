package com.devstation.android.future.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnsiParserTest {

    @Test
    fun `stripAnsi removes color and style escape sequences`() {
        val raw = "\u001b[32mHello World\u001b[0m"
        val stripped = AnsiParser.stripAnsi(raw)
        assertEquals("Hello World", stripped)

        val complex = "\u001b[1;31mError:\u001b[0m file not found \u001b[2K"
        val clean = AnsiParser.stripAnsi(complex)
        assertEquals("Error: file not found ", clean)
    }

    @Test
    fun `parseToAnnotatedString removes raw escape characters from text`() {
        val raw = "\u001b[34mDevStation\u001b[0m"
        val annotated = AnsiParser.parseToAnnotatedString(raw, Color.White)

        assertEquals("DevStation", annotated.text)
        assertFalse(annotated.text.contains("\u001b"))
    }
}
