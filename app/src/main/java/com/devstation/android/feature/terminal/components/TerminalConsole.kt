package com.devstation.android.feature.terminal.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devstation.android.future.terminal.AnsiParser
import com.devstation.android.future.terminal.OutputType
import com.devstation.android.future.terminal.TerminalOutputLine
import kotlinx.coroutines.launch

@Composable
fun TerminalConsole(
    lines: List<TerminalOutputLine>,
    fontSizeSp: Int = 12,
    autoScrollEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Determine if the user is currently at the bottom
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= totalItems - 2
        }
    }

    // Auto-scroll when new lines arrive and user is at bottom
    LaunchedEffect(lines.size) {
        if (autoScrollEnabled && isAtBottom && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(lines) { line ->
                    TerminalLineRow(
                        line = line,
                        fontSizeSp = fontSizeSp
                    )
                }
            }
        }

        // Floating Jump-to-bottom button when scrolled up
        AnimatedVisibility(
            visible = !isAtBottom && lines.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            ElevatedButton(
                onClick = {
                    scope.launch {
                        if (lines.isNotEmpty()) {
                            listState.animateScrollToItem(lines.size - 1)
                        }
                    }
                },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Scroll to bottom",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Bottom", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TerminalLineRow(
    line: TerminalOutputLine,
    fontSizeSp: Int
) {
    val defaultColor = when (line.type) {
        OutputType.STDOUT -> MaterialTheme.colorScheme.onSurface
        OutputType.STDERR -> MaterialTheme.colorScheme.error
        OutputType.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
        OutputType.COMMAND -> MaterialTheme.colorScheme.primary
    }

    val annotated = remember(line.text, defaultColor) {
        AnsiParser.parseToAnnotatedString(line.text, defaultColor)
    }

    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 4).sp,
        fontWeight = if (line.type == OutputType.COMMAND) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth()
    )
}
