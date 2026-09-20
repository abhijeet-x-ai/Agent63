package com.devstation.android.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.feature.editor.model.EditorLanguage
import com.devstation.android.feature.editor.model.EditorSettings
import com.devstation.android.feature.editor.model.EditorTab
import com.devstation.android.feature.editor.service.EditorColorScheme
import com.devstation.android.feature.editor.service.RegexSyntaxHighlighter
import com.devstation.android.feature.editor.service.SyntaxHighlighter

class SyntaxHighlightingVisualTransformation(
    private val language: EditorLanguage,
    private val colorScheme: EditorColorScheme,
    private val highlighter: SyntaxHighlighter,
    private val enabled: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (!enabled) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val highlighted = highlighter.highlight(text.text, language, colorScheme)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

@Composable
fun CodeEditorView(
    tab: EditorTab,
    settings: EditorSettings,
    colorScheme: EditorColorScheme,
    onContentChange: (String, Int) -> Unit,
    onCursorChange: (Int) -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onReplace: () -> Unit,
    onGoToLine: () -> Unit,
    highlighter: SyntaxHighlighter = remember { RegexSyntaxHighlighter() },
    modifier: Modifier = Modifier
) {
    var textFieldValue by remember(tab.id) {
        mutableStateOf(
            TextFieldValue(
                text = tab.document.content,
                selection = TextRange(tab.cursor.selectionStart, tab.cursor.selectionEnd)
            )
        )
    }

    // Keep textFieldValue text synchronized if document was modified externally or by undo/redo
    LaunchedEffect(tab.document.content) {
        if (textFieldValue.text != tab.document.content) {
            val safeSelection = TextRange(
                tab.cursor.selectionStart.coerceIn(0, tab.document.content.length),
                tab.cursor.selectionEnd.coerceIn(0, tab.document.content.length)
            )
            textFieldValue = textFieldValue.copy(
                text = tab.document.content,
                selection = safeSelection
            )
        }
    }

    // Keep cursor selection synchronized if updated by GoToLine or Search
    LaunchedEffect(tab.cursor.selectionStart) {
        if (textFieldValue.selection.start != tab.cursor.selectionStart) {
            textFieldValue = textFieldValue.copy(
                selection = TextRange(
                    tab.cursor.selectionStart.coerceIn(0, textFieldValue.text.length),
                    tab.cursor.selectionEnd.coerceIn(0, textFieldValue.text.length)
                )
            )
        }
    }

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    val visualTransformation = remember(tab.language, colorScheme, settings.enableSyntaxHighlighting) {
        SyntaxHighlightingVisualTransformation(
            language = tab.language,
            colorScheme = colorScheme,
            highlighter = highlighter,
            enabled = settings.enableSyntaxHighlighting
        )
    }

    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * 1.4f).sp,
        color = colorScheme.text
    )

    val lineCount = tab.document.lineCount
    val lineNumWidth = (lineCount.toString().length * 10 + 16).dp.coerceAtLeast(36.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        event.isCtrlPressed && event.key == Key.S -> {
                            onSave()
                            true
                        }
                        event.isCtrlPressed && event.key == Key.Z -> {
                            onUndo()
                            true
                        }
                        event.isCtrlPressed && event.key == Key.Y -> {
                            onRedo()
                            true
                        }
                        event.isCtrlPressed && event.key == Key.F -> {
                            onFind()
                            true
                        }
                        event.isCtrlPressed && event.key == Key.H -> {
                            onReplace()
                            true
                        }
                        event.isCtrlPressed && event.key == Key.G -> {
                            onGoToLine()
                            true
                        }
                        event.key == Key.Tab -> {
                            val spaces = " ".repeat(settings.tabSize)
                            val currText = textFieldValue.text
                            val currOffset = textFieldValue.selection.start
                            val newText = currText.substring(0, currOffset) + spaces + currText.substring(currOffset)
                            val newOffset = currOffset + spaces.length
                            textFieldValue = TextFieldValue(newText, TextRange(newOffset))
                            onContentChange(newText, newOffset)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
        ) {
            // Synchronized Line Numbers Column
            if (settings.showLineNumbers) {
                LineNumbersColumn(
                    lineCount = lineCount,
                    currentLine = tab.cursor.line,
                    lineNumWidth = lineNumWidth,
                    textStyle = textStyle,
                    colorScheme = colorScheme
                )
            }

            // Code Editor Field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
                    .then(
                        if (!settings.wordWrap) {
                            Modifier.horizontalScroll(horizontalScrollState)
                        } else Modifier
                    )
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onContentChange(newValue.text, newValue.selection.start)
                        onCursorChange(newValue.selection.start)
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colorScheme.text),
                    visualTransformation = visualTransformation,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .widthIn(min = 600.dp)
                )
            }
        }
    }
}

@Composable
fun LineNumbersColumn(
    lineCount: Int,
    currentLine: Int,
    lineNumWidth: androidx.compose.ui.unit.Dp,
    textStyle: TextStyle,
    colorScheme: EditorColorScheme,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(lineNumWidth)
            .background(colorScheme.currentLineBackground.copy(alpha = 0.5f))
            .padding(vertical = 2.dp)
    ) {
        val lineNumbersText = (1..lineCount).joinToString("\n")
        Text(
            text = lineNumbersText,
            style = textStyle.copy(
                color = colorScheme.lineNumber,
                textAlign = TextAlign.End
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 6.dp)
        )
    }
}
