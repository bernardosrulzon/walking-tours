package com.walkingtours.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.walkingtours.app.R
import com.walkingtours.app.audio.NarrationState

/**
 * The audio transport, kept to a **single row**.
 *
 * This is a walking tour guide, not a music player: the writing and the photographs are the product,
 * and the controls should not take a band of the screen to do five simple things. So the controls,
 * the scrubber and the speed all share one row, and there is no status line beneath it — the voice
 * in use is a setting, not something to read while walking.
 *
 * The icons are plain clickable boxes rather than [androidx.compose.material3.IconButton] because
 * Material forces a 48dp minimum touch target on that component, which alone would have made this
 * row too wide to also hold the scrubber.
 */
@Composable
fun NarrationTransport(
    state: NarrationState,
    positionMs: Long,
    durationMs: Long,
    rate: Float,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onRateChange: (Float) -> Unit,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    val playing = state == NarrationState.PLAYING || state == NarrationState.PREPARING
    val unavailable = state == NarrationState.UNAVAILABLE
    val hasTimeline = durationMs > 0L

    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val liveFraction = if (hasTimeline) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = scrubbing ?: liveFraction

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(R.drawable.ic_previous, "Previous stop", onClick = onPrevious)
        TransportButton(R.drawable.ic_rewind_15, "Rewind 15 seconds", enabled = !unavailable, onClick = onRewind)
        FilledIconButton(
            onClick = onPlayPause,
            enabled = !unavailable,
            modifier = Modifier.size(46.dp),
        ) {
            Icon(
                painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(24.dp),
            )
        }
        TransportButton(R.drawable.ic_forward_15, "Forward 15 seconds", enabled = !unavailable, onClick = onForward)
        TransportButton(R.drawable.ic_next, "Next stop", onClick = onNext)

        Spacer(Modifier.width(8.dp))
        MinimalScrubber(
            fraction = shownFraction,
            enabled = hasTimeline && !unavailable,
            onScrub = { scrubbing = it },
            onCommit = { value ->
                scrubbing = null
                onSeekFraction(value)
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable {
                val options = listOf(0.75f, 1.0f, 1.25f, 1.5f)
                val next = options[(options.indexOfFirst { kotlin.math.abs(it - rate) < 0.01f } + 1)
                    .mod(options.size)]
                onRateChange(next)
            },
        ) {
            Text(
                text = if (kotlin.math.abs(rate - 1.0f) < 0.01f) "1x" else "${rate}x",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }

    // Only surfaced when something is actually wrong; the normal states say nothing.
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * A transport icon sized to the row rather than to Material's 48dp touch target. It still has an
 * accessible label, and the row is horizontal so mis-taps land on a neighbour rather than nowhere.
 */
@Composable
private fun TransportButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(22.dp),
        )
    }
}


/**
 * A deliberately quiet scrubber: a hairline with a small dot, in place of Material's slider with its
 * thick track and haloed thumb.
 *
 * This page is mostly writing and photographs, and a heavy control in the middle of it pulls the eye
 * away from the thing the walker came for. Progress still has to be visible and seekable, so it stays
 * — it is just no longer the loudest element on the screen.
 */
@Composable
private fun MinimalScrubber(
    fraction: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val progressColor = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.primary

    var lastFraction by remember { mutableStateOf(fraction) }

    Canvas(
        modifier = modifier
            .height(22.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val target = if (size.width <= 0) 0f else (offset.x / size.width).coerceIn(0f, 1f)
                    lastFraction = target
                    onScrub(target)
                    onCommit(target)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val target = if (size.width <= 0) 0f else (offset.x / size.width).coerceIn(0f, 1f)
                        lastFraction = target
                        onScrub(target)
                    },
                    onDragEnd = { onCommit(lastFraction) },
                    onDragCancel = { onCommit(lastFraction) },
                    onHorizontalDrag = { change, _ ->
                        val target = if (size.width <= 0) {
                            0f
                        } else {
                            (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                        lastFraction = target
                        onScrub(target)
                    },
                )
            },
    ) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val usable = (size.width - stroke).coerceAtLeast(1f)
        val centreY = size.height / 2f
        val shown = fraction.coerceIn(0f, 1f)

        drawLine(
            color = trackColor,
            start = Offset(inset, centreY),
            end = Offset(inset + usable, centreY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = progressColor,
            start = Offset(inset, centreY),
            end = Offset(inset + usable * shown, centreY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = thumbColor,
            radius = 4.5.dp.toPx(),
            center = Offset(inset + usable * shown, centreY),
        )
    }
}
