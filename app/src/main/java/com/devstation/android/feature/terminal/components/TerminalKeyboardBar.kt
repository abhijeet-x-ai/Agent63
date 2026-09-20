package com.devstation.android.feature.terminal.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalKeyboardBar(
    onEscape: () -> Unit,
    onTab: () -> Unit,
    onCtrlC: () -> Unit,
    onCtrlD: () -> Unit,
    onCtrlL: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onInsertSymbol: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                KeyButton("ESC", isHighlight = false, onClick = onEscape)
            }
            item {
                KeyButton("TAB", isHighlight = false, onClick = onTab)
            }
            item {
                KeyButton("CTRL+C", isHighlight = true, highlightColor = MaterialTheme.colorScheme.error, onClick = onCtrlC)
            }
            item {
                KeyButton("CTRL+D", isHighlight = false, onClick = onCtrlD)
            }
            item {
                KeyButton("CTRL+L", isHighlight = false, onClick = onCtrlL)
            }
            item {
                KeyButton("↑", isHighlight = false, onClick = onHistoryUp)
            }
            item {
                KeyButton("↓", isHighlight = false, onClick = onHistoryDown)
            }
            item {
                KeyButton("/", isHighlight = false, onClick = { onInsertSymbol("/") })
            }
            item {
                KeyButton("-", isHighlight = false, onClick = { onInsertSymbol("-") })
            }
            item {
                KeyButton("|", isHighlight = false, onClick = { onInsertSymbol("|") })
            }
            item {
                KeyButton("~", isHighlight = false, onClick = { onInsertSymbol("~") })
            }
            item {
                KeyButton("_", isHighlight = false, onClick = { onInsertSymbol("_") })
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    isHighlight: Boolean = false,
    highlightColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (isHighlight) highlightColor else MaterialTheme.colorScheme.onSurface
        )
    }
}
