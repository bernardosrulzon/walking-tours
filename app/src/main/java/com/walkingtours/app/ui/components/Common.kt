package com.walkingtours.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Compact rounded label, e.g. "14 stops" or "2.8 km". Used instead of chips to keep styling simple. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/** Icon, small grey label, then the value. The workhorse row of the stop detail screen. */
@Composable
fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (value.isBlank()) return
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
            )
        }
    }
}

/**
 * Renders the spoken narration as plain prose.
 *
 * It used to highlight the characters currently being spoken, but that only works when the speech
 * engine reports word boundaries. The Gemini voice returns finished audio with no timings, so the
 * highlight was a proportional guess that drifted from the voice; it has been removed rather than
 * left to lie. The transcript and the transport's progress bar stay.
 */
@Composable
fun Transcript(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Text(text = text, modifier = modifier, style = style)
}
