package com.walkingtours.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The "visited" control: a ring that fills in with a tick when the stop has been reached.
 *
 * A stock Material checkbox reads as a form field, which is the wrong language for "I have been
 * here" on a travel guide. A ring that fills in is closer to the tick a printed guidebook leaves in
 * the margin, and the spring gives it a small amount of overshoot so ticking feels like something
 * happened.
 */
@Composable
fun VisitedCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    /** Shown inside the ring until it is ticked: the stop's place in the route. */
    number: Int? = null,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        // Slightly under-damped: it overshoots and settles, which is what makes the tick feel like
        // a deliberate action rather than a state change.
        animationSpec = spring(
            dampingRatio = 0.45f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "visitedProgress",
    )

    val ringColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val fillColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable { onCheckedChange(!checked) }
            .semantics {
                contentDescription = if (checked) {
                    "Marked as visited, tap to unmark"
                } else {
                    "Mark as visited"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 2.dp.toPx()
            val radius = this.size.minDimension / 2f - strokeWidth / 2f
            val fill = progress.coerceIn(0f, 1f)

            // The ring stays put so the control does not move as it fills.
            drawCircle(color = ringColor, radius = radius, style = Stroke(width = strokeWidth))
            if (fill > 0f) {
                drawCircle(color = fillColor, radius = radius * fill)
            }
        }
        // The number fades out as the fill sweeps over it, so the ring reads as the same object
        // changing state rather than one thing being replaced by another.
        if (number != null) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    alpha = (1f - progress.coerceIn(0f, 1f))
                },
            )
        }
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = tickColor,
            modifier = Modifier
                .size(size * 0.6f)
                .graphicsLayer {
                    val fill = progress.coerceIn(0f, 1f)
                    scaleX = fill
                    scaleY = fill
                    alpha = fill
                },
        )
    }
}
