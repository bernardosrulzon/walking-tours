package com.walkingtours.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.walkingtours.app.audio.NarrationState

/**
 * Transport controls for narration, shared by the stop screen and the active-tour screen.
 *
 * Playback speed matters more than it looks: a listener walking a busy street often wants 1.25x,
 * and someone reading along wants 0.75x.
 */
@Composable
fun PlaybackControls(
    state: NarrationState,
    rate: Float,
    engineLabel: String,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onRateChange: (Float) -> Unit,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    val isPlaying = state == NarrationState.PLAYING || state == NarrationState.PREPARING
    val unavailable = state == NarrationState.UNAVAILABLE

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = onPlayPause,
                enabled = !unavailable,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause narration" else "Play narration",
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRestart, enabled = !unavailable) {
                Icon(Icons.Filled.Refresh, contentDescription = "Restart narration")
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (state) {
                        NarrationState.PREPARING -> "Starting\u2026"
                        NarrationState.PLAYING -> "Playing"
                        NarrationState.PAUSED -> "Paused"
                        NarrationState.COMPLETED -> "Finished"
                        NarrationState.UNAVAILABLE -> "Audio unavailable"
                        NarrationState.IDLE -> "Ready"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = engineLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { option ->
                val selected = kotlin.math.abs(rate - option) < 0.01f
                Surface(
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    onClick = { onRateChange(option) },
                ) {
                    Text(
                        text = if (option == 1.0f) "1x" else "${option}x",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

