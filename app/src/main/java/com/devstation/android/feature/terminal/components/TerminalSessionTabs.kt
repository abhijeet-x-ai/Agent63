package com.devstation.android.feature.terminal.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.devstation.android.future.terminal.TerminalSession
import com.devstation.android.future.terminal.TerminalState

@Composable
fun TerminalSessionTabs(
    sessions: List<TerminalSession>,
    activeSessionId: String?,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onNewSession: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(sessions, key = { it.id }) { session ->
                    val isSelected = session.id == activeSessionId
                    SessionTabItem(
                        title = session.title,
                        isSelected = isSelected,
                        state = session.state.value,
                        canClose = sessions.size > 1,
                        onClick = { onSelectSession(session.id) },
                        onClose = { onCloseSession(session.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onNewSession,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Terminal Session",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionTabItem(
    title: String,
    isSelected: Boolean,
    state: TerminalState,
    canClose: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val stateColor = when (state) {
        TerminalState.RUNNING -> MaterialTheme.colorScheme.secondary
        TerminalState.STARTING -> MaterialTheme.colorScheme.tertiary
        TerminalState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        TerminalState.STOPPING -> MaterialTheme.colorScheme.tertiary
        TerminalState.FAILED -> MaterialTheme.colorScheme.error
        TerminalState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(stateColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )

        if (canClose) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Tab",
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable { onClose() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
