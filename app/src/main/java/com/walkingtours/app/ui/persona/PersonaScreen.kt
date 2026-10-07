package com.walkingtours.app.ui.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

    LaunchedEffect(step, explorers) {
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
            "Five voices for this walk, chosen for ${explorers.joinToString(", then ") { it.label }}. " +
                "This is who you'll hear at every stop."
        } else {
            "Five voices for this walk. This is who you'll hear at every stop."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onChangeExplorer) {
        Text("Prefer something else? Change your interests")
    }
    Spacer(Modifier.height(8.dp))

    // The language is chosen with the guide rather than in Settings: this is the moment the
    // walker decides what they will hear, so the voice and the tongue are picked together.
    Text(
        text = "Narration language",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NARRATION_LANGUAGES.forEach { (tag, label) ->
            val selected = language == tag
            Card(
                modifier = Modifier.weight(1f).clickable { onLanguage(tag) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (guides == null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "Finding guides for this walk\u2026",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    guides.forEach { guide ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable { onPick(guide) },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
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
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = guide.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (guide.tagline.isNotBlank()) {
                        Text(
                            text = guide.tagline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}
