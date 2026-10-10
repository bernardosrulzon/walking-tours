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
import com.walkingtours.app.ai.THEME_MODES
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

    // Live status of the Gemini key: a models call is the cheapest real test that it works.
    var apiStatus by remember { mutableStateOf<String?>(null) }
    var apiOk by remember { mutableStateOf<Boolean?>(null) }

    fun checkApi() {
        if (!state.hasGeminiKey) {
            apiOk = false
            apiStatus = "No Gemini API key in this build"
            return
        }
        apiOk = null
        apiStatus = "Checking\u2026"
        scope.launch {
            try {
                ServiceLocator.geminiClient.listModels()
                apiOk = true
                apiStatus = "Gemini API working"
            } catch (e: Exception) {
                apiOk = false
                apiStatus = "Gemini API not working: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { checkApi() }

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
                            Text("Use Gemini voice", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (state.hasGeminiKey) {
                                    "Expressive, acted narration. Billed per request after a free allowance."
                                } else {
                                    "No Gemini key in this build, so the phone's own voice is used."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.useCloudVoice,
                            enabled = state.hasGeminiKey,
                            onCheckedChange = { on -> settings.update { it.copy(useCloudVoice = on) } },
                        )
                    }
                    StatusLine(
                        text = apiStatus ?: "Checking\u2026",
                        isError = apiOk == false,
                        isSuccess = apiOk == true,
                    )
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
