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
import androidx.compose.material3.Button
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
import com.walkingtours.app.ai.ExplorerType
import com.walkingtours.app.ai.Guide
import com.walkingtours.app.ai.MAX_EXPLORER_PREFERENCES
import com.walkingtours.app.ai.NARRATION_LANGUAGES

/** Which of the two questions is on screen. */
private enum class Step { EXPLORER, GUIDE }

/**
 * The persona flow, shown before a walk begins.
 *
 * One question the first time — what kind of explorer are you — and then the guides that answer
 * suggests, fetched for this tour. The explorer answer is remembered once; the guide is remembered
 * per tour, and either can be changed later from the tour page.
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
    val persona by personaSettings.state.collectAsStateWithLifecycle()
    val aiSettings = ServiceLocator.aiSettings
    val aiState by aiSettings.state.collectAsStateWithLifecycle()

    // The explorer question is skipped once it has been answered, which is what makes it asked
    // exactly once.
    var step by remember {
        mutableStateOf(if (persona.explorers.isEmpty()) Step.EXPLORER else Step.GUIDE)
    }
    var guides by remember { mutableStateOf<List<Guide>?>(null) }
    val explorers = persona.explorers

    LaunchedEffect(step, explorers, aiState.narrationLanguage) {
        if (step != Step.GUIDE || explorers.isEmpty()) return@LaunchedEffect
        guides = null
        guides = guideController.suggestGuides(tourId, explorers)
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
            when (step) {
                Step.EXPLORER -> ExplorerStep(initial = persona.explorers) { picked ->
                    personaSettings.setExplorers(picked)
                    step = Step.GUIDE
                }

                Step.GUIDE -> GuideStep(
                    explorers = explorers,
                    guides = guides,
                    language = aiState.narrationLanguage,
                    onLanguage = { tag -> aiSettings.update { it.copy(narrationLanguage = tag) } },
                    onChangeExplorer = { step = Step.EXPLORER },
                    onPick = { guide ->
                        personaSettings.setGuide(tourId, guide)
                        onDone()
                    },
                )
            }
        }
    }
}

@Composable
private fun ExplorerStep(
    initial: List<ExplorerType>,
    onContinue: (List<ExplorerType>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }

    Text(
        text = "What kind of explorer are you?",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Pick up to three, in the order that matters most to you. Your guide leads with the " +
            "first and weaves in the rest. You answer this once, and you can change it later.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    ExplorerType.entries.forEach { type ->
        val rank = selected.indexOf(type)
        val isSelected = rank >= 0
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable {
                    selected = when {
                        isSelected -> selected - type
                        selected.size < MAX_EXPLORER_PREFERENCES -> selected + type
                        else -> selected
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(type.emoji, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${rank + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    if (selected.size >= MAX_EXPLORER_PREFERENCES) {
        Text(
            text = "That's your top three. Tap one to swap it out.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = { onContinue(selected) },
        enabled = selected.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (selected.isEmpty()) "Pick at least one" else "Continue")
    }
}

@Composable
private fun GuideStep(
    explorers: List<ExplorerType>,
    guides: List<Guide>?,
    language: String,
    onLanguage: (String) -> Unit,
    onChangeExplorer: () -> Unit,
    onPick: (Guide) -> Unit,
) {
    Text(
        text = "Choose your guide",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (explorers.isNotEmpty()) {
            "Four voices for this walk, chosen for ${explorers.joinToString(", then ") { it.label }}. " +
                "This is who you'll hear at every stop."
        } else {
            "Four voices for this walk. This is who you'll hear at every stop."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    // One compact control row: change the interests on the left, pick the language on the right.
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onChangeExplorer,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text("Change interests", style = MaterialTheme.typography.labelLarge)
        }
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
        modifier = modifier.height(168.dp).clickable { onPick(guide) },
        // A quiet surface with a hairline edge, rather than a block of primary blue: the colour was
        // competing with the avatar and made the grid look washed out.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
            if (guide.tagline.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = guide.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
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
        modifier = modifier.height(168.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
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
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
    )
}
