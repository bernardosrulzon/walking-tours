package com.walkingtours.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ai.CloudVoice
import com.walkingtours.app.ai.NARRATION_LANGUAGE
import kotlinx.coroutines.launch

private const val TEST_PHRASE =
    "This is how the guide will sound. Standing in the middle of what was once the Hippodrome, " +
        "this obelisk is far older than Istanbul itself."

/**
 * Settings for the two optional Google services.
 *
 * Nothing here is required: with no keys at all the app still runs a complete tour using the phone's
 * own voice. These settings are how a user upgrades the narration and switches the assistant on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = ServiceLocator.aiSettings
    val state by settings.state.collectAsStateWithLifecycle()
    val narration = ServiceLocator.narrationEngine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var voices by remember { mutableStateOf<List<CloudVoice>>(emptyList()) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var loadingVoices by remember { mutableStateOf(false) }
    var geminiStatus by remember { mutableStateOf<String?>(null) }
    var ttsStatus by remember { mutableStateOf<String?>(null) }
    var resolvedModel by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "The tour works with no accounts at all. The voice and the travel " +
                        "assistant are already set up for this build.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------------------------------------------------- narration voice
            item {
                SettingsCard(title = "Narration voice") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use Google Cloud voice", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (state.hasTtsKey) {
                                    "Higher quality, billed per character after a monthly free allowance."
                                } else {
                                    "Add a Text-to-Speech API key below to enable this."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.useCloudVoice,
                            enabled = state.hasTtsKey,
                            onCheckedChange = { on -> settings.update { it.copy(useCloudVoice = on) } },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    StatusLine("Currently using: ${narration.engineLabel}")

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Voice: ${state.cloudVoiceName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                loadingVoices = true
                                voiceStatus = null
                                scope.launch {
                                    try {
                                        voices = ServiceLocator.cloudTtsClient
                                            .listVoices(NARRATION_LANGUAGE)
                                            .sortedWith(compareBy({ it.family }, { it.shortName }))
                                        voiceStatus = if (voices.isEmpty()) {
                                            "No voices returned for $NARRATION_LANGUAGE."
                                        } else {
                                            "${voices.size} voices available."
                                        }
                                    } catch (e: Exception) {
                                        voiceStatus = e.message
                                    } finally {
                                        loadingVoices = false
                                    }
                                }
                            },
                            enabled = state.hasTtsKey && !loadingVoices,
                        ) {
                            if (loadingVoices) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("Load voices")
                        }
                        OutlinedButton(
                            onClick = {
                                narration.stop()
                                narration.play("__voice_test__", TEST_PHRASE)
                            },
                            enabled = state.hasTtsKey,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Test")
                        }
                    }

                    voiceStatus?.let { StatusLine(it) }

                    if (voices.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        voices.forEach { voice ->
                            VoiceRow(
                                voice = voice,
                                selected = voice.name == state.cloudVoiceName,
                                onSelect = {
                                    settings.update {
                                        it.copy(cloudVoiceName = voice.name, useCloudVoice = true)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------- assistant
            item {
                SettingsCard(title = "Travel assistant") {
                    Text(
                        text = "Answers questions about this stop, the city, its history, food and " +
                            "practicalities. Questions outside travel are declined.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Read answers aloud", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Uses the same voice as the tour narration.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.speakAiAnswers,
                            onCheckedChange = { on -> settings.update { it.copy(speakAiAnswers = on) } },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            geminiStatus = "Checking\u2026"
                            scope.launch {
                                geminiStatus = try {
                                    val models = ServiceLocator.geminiClient.listModels()
                                    val chosen = ServiceLocator.geminiClient
                                        .resolveModel(state.geminiModel)
                                    resolvedModel = chosen
                                    "Connected. ${models.size} models available. Using: $chosen"
                                } catch (e: Exception) {
                                    e.message
                                }
                            }
                        },
                        enabled = state.hasGeminiKey,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test connection and detect model")
                    }
                    geminiStatus?.let { StatusLine(it, isError = resolvedModel == null && it != "Checking\u2026") }
                    if (resolvedModel == null && geminiStatus == null) {
                        StatusLine(
                            if (state.hasGeminiKey) {
                                "Using the best available model automatically. Leave the model field " +
                                    "blank to keep it that way."
                            } else {
                                "Add a Gemini API key below to switch the assistant on."
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.geminiModel,
                        onValueChange = { value -> settings.update { it.copy(geminiModel = value) } },
                        label = { Text("Model (blank = choose automatically)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---------------------------------------------------------- keys
        }
    }
}

@Composable
private fun SetupSteps() {
    val steps = listOf(
        "Create a Google Cloud project, or reuse one you already have.",
        "Turn on billing for that project. Cloud Text-to-Speech requires it even inside the free allowance.",
        "Enable the Cloud Text-to-Speech API from the APIs & Services library.",
        "Create an API key under APIs & Services, then Credentials.",
        "Restrict the key: Application restrictions \u2192 Android apps, and add package name " +
            "com.walkingtours.app plus your signing certificate SHA-1.",
        "Under API restrictions, limit the key to Cloud Text-to-Speech API.",
        "For the assistant, get a Gemini key from Google AI Studio and paste it above. It can live in " +
            "the same project.",
        "Paste the keys above and use the Test buttons to confirm everything works.",
    )
    Column {
        steps.forEachIndexed { index, step ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(22.dp),
                )
                Text(text = step, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatusLine(text: String, isError: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceRow(voice: CloudVoice, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = voice.shortName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${voice.family} \u00b7 ${voice.gender.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
