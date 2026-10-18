package com.walkingtours.app.ui.persona

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ai.Guide
import com.walkingtours.app.ai.NARRATION_LANGUAGES

/** Which of the two questions is on screen. */
private enum class Step { EXPLORER, GUIDE }

/**
 * The guide picker, shown before a walk begins.
 *
 * One question — who tells this walk — answered by four guides generated for this tour, each
 * dwelling on a different aspect of the place. The choice is remembered per tour and can be changed
 * later from the tour page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(
    tourId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val personaSettings = ServiceLocator.personaSettings
    val guideController = ServiceLocator.guide
    val aiSettings = ServiceLocator.aiSettings
    val aiState by aiSettings.state.collectAsStateWithLifecycle()

    var guides by remember { mutableStateOf<List<Guide>?>(null) }

    LaunchedEffect(tourId, aiState.narrationLanguage) {
        guides = null
        guides = guideController.suggestGuides(tourId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your guide", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            GuideStep(
                guides = guides,
                language = aiState.narrationLanguage,
                onLanguage = { tag -> aiSettings.update { it.copy(narrationLanguage = tag) } },
                onPick = { guide ->
                    personaSettings.setGuide(tourId, guide)
                    // The voice follows the guide's gender, so a guide never sounds like the
                    // wrong person. Applied here, where the choice is made, and re-checked
                    // before every play in case the voice was changed afterwards.
                    guideController.ensureVoiceMatchesGuide(tourId)
                    onDone()
                },
            )
        }
    }
}

@Composable
private fun GuideStep(
    guides: List<Guide>?,
    language: String,
    onLanguage: (String) -> Unit,
    onPick: (Guide) -> Unit,
) {
    Text(
        text = "Choose your guide",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Four voices for this walk, each dwelling on a different side of it — the choice is " +
            "what you want more of. This is who you'll hear at every stop.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    // One compact control row: the language on the right, since the guide cards fill the rest.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        LanguageDropdown(selected = language, onSelect = onLanguage)
    }
    Spacer(Modifier.height(16.dp))

    if (guides == null) {
        // Card skeletons in the loaded layout — same size, same two columns — so the page does not
        // jump when the guides arrive, and the walker sees the shape of what is coming.
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GuideCardSkeleton(Modifier.weight(1f))
                GuideCardSkeleton(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        return
    }

    // Two columns: four cards fit in two short rows, and the page stays compact.
    guides.chunked(2).forEach { pair ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair.forEach { guide ->
                GuideCard(guide = guide, modifier = Modifier.weight(1f), onPick = onPick)
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LanguageDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = NARRATION_LANGUAGES.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Narration language",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NARRATION_LANGUAGES.forEach { (tag, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(tag)
                    },
                )
            }
        }
    }
}

@Composable
private fun GuideCard(guide: Guide, modifier: Modifier, onPick: (Guide) -> Unit) {
    Card(
        modifier = modifier.height(196.dp).clickable { onPick(guide) },
        // A quiet surface with a hairline edge, rather than a block of primary blue: the colour was
        // competing with the avatar and made the grid look washed out.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = guide.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = guide.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (guide.angle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                // The aspect is the real difference between the four cards, so it is the line the eye
                // lands on: the label colour, above the tagline rather than below it.
                Text(
                    text = guide.angle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            if (guide.tagline.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = guide.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A placeholder shaped like a [GuideCard], pulsing gently while the guides are generated. Same size
 * and layout as the real thing, so nothing moves when they arrive.
 */
@Composable
private fun GuideCardSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "guide-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "guide-skeleton-alpha",
    )
    Card(
        modifier = modifier.height(196.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)),
            )
            Spacer(Modifier.height(10.dp))
            SkeletonBar(widthFraction = 0.72f, height = 12.dp, alpha = alpha)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(widthFraction = 0.5f, height = 12.dp, alpha = alpha)
            Spacer(Modifier.height(12.dp))
            SkeletonBar(widthFraction = 0.92f, height = 10.dp, alpha = alpha)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(widthFraction = 0.82f, height = 10.dp, alpha = alpha)
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp, alpha: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
    )
}
