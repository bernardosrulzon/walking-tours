package com.walkingtours.app.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ai.MODEL_PROVIDERS
import com.walkingtours.app.ai.MODEL_PROVIDER_OPENCODE
import com.walkingtours.app.ai.THEME_MODES
import com.walkingtours.app.ai.TTS_ENGINES
import com.walkingtours.app.ai.TTS_ENGINE_ELEVENLABS
import kotlinx.coroutines.launch

/**
 * Settings for the optional Gemini services.
 *
 * Nothing here is required: with no key at all the app still runs a complete tour on the phone's own
 * voice. These settings switch the upgraded narration on and report whether the key works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = ServiceLocator.aiSettings
    val state by settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Live status of the text-model provider: a models call is the cheapest real test that the key
    // works, and it also proves the pinned model is offered to this account.
    var apiStatus by remember { mutableStateOf<String?>(null) }
    var apiOk by remember { mutableStateOf<Boolean?>(null) }
    val openCode = state.modelProvider == MODEL_PROVIDER_OPENCODE
    val providerName = if (openCode) "OpenCode" else "Gemini"

    fun checkApi() {
        if (!state.modelProviderReady) {
            apiOk = false
            apiStatus = if (openCode) {
                "No OpenCode key in this build"
            } else {
                "No Gemini API key in this build"
            }
            return
        }
        apiOk = null
        apiStatus = "Checking\u2026"
        scope.launch {
            try {
                val client = ServiceLocator.modelClient
                val models = client.availableModels()
                val wanted = client.activeModel
                // The app calls one pinned model, so a working key that cannot see it is a failure
                // worth naming here rather than in the middle of a walk.
                if (models.none { it == wanted }) {
                    apiOk = false
                    apiStatus = "$wanted is not available on this key"
                } else {
                    apiOk = true
                    apiStatus = if (openCode) {
                        "OpenCode key accepted \u00b7 $wanted"
                    } else {
                        "Gemini API working \u00b7 $wanted"
                    }
                }
            } catch (e: Exception) {
                apiOk = false
                apiStatus = "$providerName not working: ${e.message}"
            }
        }
    }

    LaunchedEffect(state.modelProvider) { checkApi() }

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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------------------------------------------------------- models
            item {
                SettingsCard(title = "AI models") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Model provider", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (openCode) {
                                    "Guides, rewrites, detours and chat run through OpenCode, " +
                                        "using Gemini 3.8 Flash."
                                } else {
                                    "Guides, rewrites, detours and chat run on Google Gemini, " +
                                        "billed per token."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        ModelProviderDropdown(
                            selected = state.modelProvider,
                            onSelect = { id -> settings.update { it.copy(modelProvider = id) } },
                        )
                    }
                    StatusLine(
                        text = apiStatus ?: "Checking\u2026",
                        isError = apiOk == false,
                        isSuccess = apiOk == true,
                    )
                    if (openCode) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "OpenCode serves Gemini 3.8 Flash through its pay-as-you-go " +
                                "gateway. The Go subscription alone does not include it, so this " +
                                "needs Console credits.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------------------------------------------------- appearance
            item {
                SettingsCard(title = "Appearance") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Theme",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        ThemeDropdown(
                            selected = state.themeMode,
                            onSelect = { tag -> settings.update { it.copy(themeMode = tag) } },
                        )
                    }
                }
            }

            // ---------------------------------------------------------- narration voice
            item {
                SettingsCard(title = "Narration voice") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use a cloud voice", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = when {
                                    state.ttsEngine == TTS_ENGINE_ELEVENLABS && !state.hasElevenLabsKey ->
                                        "No ElevenLabs key in this build, so the phone's own voice is used."
                                    state.ttsEngine != TTS_ENGINE_ELEVENLABS && !state.hasGeminiKey ->
                                        "No Gemini key in this build, so the phone's own voice is used."
                                    state.ttsEngine == TTS_ENGINE_ELEVENLABS ->
                                        "ElevenLabs reads the guide in a voice cast for that character. " +
                                            "Billed per character."
                                    else ->
                                        "Gemini reads the guide. Billed per request after a free allowance."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.useCloudVoice,
                            enabled = state.cloudEngineReady,
                            onCheckedChange = { on -> settings.update { it.copy(useCloudVoice = on) } },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Voice engine",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        VoiceEngineDropdown(
                            selected = state.ttsEngine,
                            onSelect = { id ->
                                val ready = if (id == TTS_ENGINE_ELEVENLABS) {
                                    state.hasElevenLabsKey
                                } else {
                                    state.hasGeminiKey
                                }
                                settings.update {
                                    it.copy(ttsEngine = id, useCloudVoice = it.useCloudVoice && ready)
                                }
                            },
                        )
                    }
                    if (state.ttsEngine == TTS_ENGINE_ELEVENLABS) {
                        StatusLine(
                            text = if (state.hasElevenLabsKey) {
                                "ElevenLabs key configured. Each guide speaks in its own cast voice; " +
                                    "audio tags like [laughs] and [sighs] are performed."
                            } else {
                                "No ElevenLabs key in this build. Add elevenlabs.apiKey to local.properties."
                            },
                            isError = !state.hasElevenLabsKey,
                            isSuccess = state.hasElevenLabsKey,
                        )
                    } else {
                        StatusLine(
                            text = if (state.hasGeminiKey) {
                                "Gemini TTS key configured. This is separate from the model provider above."
                            } else {
                                "No Gemini key in this build, so the phone's own voice is used."
                            },
                            isError = !state.hasGeminiKey,
                            isSuccess = state.hasGeminiKey,
                        )
                    }
                }
            }

            // ---------------------------------------------------------- playback
            item {
                SettingsCard(title = "Chapter playback") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Play chapters automatically", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (state.autoPlayChapters) {
                                    "The introduction plays when the tour starts, and each stop's " +
                                        "narration starts as soon as you arrive."
                                } else {
                                    "Nothing plays by itself. The introduction and each stop wait for " +
                                        "you to press play."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.autoPlayChapters,
                            onCheckedChange = { on -> settings.update { it.copy(autoPlayChapters = on) } },
                        )
                    }
                }
            }
        }
    }
}

/** A compact theme picker: the current mode on a button, the rest in a menu. */
@Composable
private fun ThemeDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = THEME_MODES.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Appearance",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            THEME_MODES.forEach { (tag, text) ->
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

/** A compact text-model provider picker. */
@Composable
private fun ModelProviderDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = MODEL_PROVIDERS.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Model provider",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MODEL_PROVIDERS.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

/** A compact cloud-voice picker: the current engine on a button, the rest in a menu. */
@Composable
private fun VoiceEngineDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = TTS_ENGINES.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Voice engine",
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TTS_ENGINES.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
private fun StatusLine(text: String, isError: Boolean = false, isSuccess: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = when {
                isSuccess -> Icons.Filled.Check
                isError -> Icons.Filled.Warning
                else -> Icons.Filled.Info
            },
            contentDescription = null,
            tint = when {
                isSuccess -> MaterialTheme.colorScheme.primary
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                isSuccess -> MaterialTheme.colorScheme.primary
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
