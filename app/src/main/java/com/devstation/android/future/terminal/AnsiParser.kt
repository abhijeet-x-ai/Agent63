package com.devstation.android.future.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

object AnsiParser {

    private val ANSI_ESCAPE_REGEX = Regex("\u001b\\[[0-9;?]*[a-zA-Z]")

    private val colorMap = mapOf(
        30 to Color(0xFF4B5563), // Black / Dark Gray
        31 to Color(0xFFEF4444), // Red
        32 to Color(0xFF10B981), // Green
        33 to Color(0xFFF59E0B), // Yellow
        34 to Color(0xFF3B82F6), // Blue
        35 to Color(0xFF8B5CF6), // Magenta / Purple
        36 to Color(0xFF06B6D4), // Cyan
        37 to Color(0xFFE5E7EB), // White / Light Gray

        // Bright / High intensity colors
        90 to Color(0xFF6B7280), // Bright Black / Gray
        91 to Color(0xFFF87171), // Bright Red
        92 to Color(0xFF34D399), // Bright Green
        93 to Color(0xFFFBBF24), // Bright Yellow
        94 to Color(0xFF60A5FA), // Bright Blue
        95 to Color(0xFFA78BFA), // Bright Magenta
        96 to Color(0xFF22D3EE), // Bright Cyan
        97 to Color(0xFFFFFFFF)  // Bright White
    )

    fun stripAnsi(text: String): String {
        return text.replace(ANSI_ESCAPE_REGEX, "")
    }

    fun parseToAnnotatedString(
        rawText: String,
        defaultColor: Color
    ): AnnotatedString {
        val matches = ANSI_ESCAPE_REGEX.findAll(rawText).toList()
        if (matches.isEmpty()) {
            return AnnotatedString(rawText)
        }

        return buildAnnotatedString {
            var currentIndex = 0
            var currentColor: Color = defaultColor
            var isBold = false

            for (match in matches) {
                if (match.range.first > currentIndex) {
                    val segment = rawText.substring(currentIndex, match.range.first)
                    val start = length
                    append(segment)
                    addStyle(
                        SpanStyle(
                            color = currentColor,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                        ),
                        start = start,
                        end = length
                    )
                }

                val escapeCode = match.value
                if (escapeCode.endsWith("m")) {
                    val codeContent = escapeCode.removePrefix("\u001b[").removeSuffix("m")
                    if (codeContent.isEmpty() || codeContent == "0") {
                        currentColor = defaultColor
                        isBold = false
                    } else {
                        val codes = codeContent.split(";").mapNotNull { it.toIntOrNull() }
                        for (code in codes) {
                            when (code) {
                                0 -> {
                                    currentColor = defaultColor
                                    isBold = false
                                }
                                1 -> isBold = true
                                22 -> isBold = false
                                39 -> currentColor = defaultColor
                                in colorMap.keys -> currentColor = colorMap[code] ?: defaultColor
                            }
                        }
                    }
                }

                currentIndex = match.range.last + 1
            }

            if (currentIndex < rawText.length) {
                val segment = rawText.substring(currentIndex)
                val start = length
                append(segment)
                addStyle(
                    SpanStyle(
                        color = currentColor,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                    ),
                    start = start,
                    end = length
                )
            }
        }
    }
}
