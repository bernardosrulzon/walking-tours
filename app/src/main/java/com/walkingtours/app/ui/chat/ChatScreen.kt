package com.walkingtours.app.ui.chat

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ai.ChatMessage
import com.walkingtours.app.ai.ChatRole
import com.walkingtours.app.ai.Suggestion
import com.walkingtours.app.ai.TravelChatController

/**
 * The docked "ask" bar.
 *
 * Sits at the bottom of the walking screens so the assistant is always one tap away, which matters
 * exactly at the moment a question occurs to you: standing in front of the thing you are curious
 * about. Tapping it opens the conversation over the top of the tour rather than navigating away, so
 * the audio and the map keep running underneath.
 */
@Composable
fun AskBar(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            // Scaffold does not inset its bottomBar, so without this the bar sits underneath the
            // system navigation bar and cannot be tapped at all on a device using 3-button
            // navigation. The emulator's gesture navigation hid this.
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Ask by voice",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The same conversation, presented as a sheet over the tour, so the map and the audio keep running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBottomSheet(
    tourId: String,
    stopId: String?,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val controller = ServiceLocator.chat
    val state by controller.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Without this the controller has no conversation key, so asking a question would silently do
    // nothing and the suggested questions would never load. The full-screen chat always opened a
    // conversation; the sheet has to do the same.
    LaunchedEffect(tourId, stopId) { controller.open(tourId, stopId) }

    // Opening a sheet that already covers most of the screen before you have asked anything feels
    // wrong. Size to the content until there is a conversation to show, then grow.
    val compact = state.messages.isEmpty() && !state.isSending

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Handle the navigation bar inset ourselves: the sheet's default inset handling left the
        // input row underneath the system navigation bar on a 3-button-navigation device.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        ChatContent(
            controller = controller,
            onOpenSettings = onOpenSettings,
            compact = compact,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier else Modifier.fillMaxHeight(0.9f))
                .navigationBarsPadding(),
        )
    }
}

/**
 * Everything the user interacts with, shared by the full screen and the sheet so the two can never
 * drift apart.
 */
@Composable
fun ChatContent(
    controller: TravelChatController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when the sheet should hug its content rather than fill the screen. */
    compact: Boolean = false,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // Keep the newest exchange in view as the conversation grows.
    LaunchedEffect(state.messages.size, state.isSending) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (spoken.isNotBlank()) {
            controller.ask(spoken)
            draft = ""
        }
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about this stop")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        // The recogniser is a separate app, so it can legitimately be missing on a bare device.
        runCatching { voiceLauncher.launch(intent) }
    }

    fun send() {
        val question = draft.trim()
        if (question.isEmpty()) return
        controller.ask(question)
        draft = ""
    }

    Column(modifier = modifier.imePadding()) {
        if (state.needsApiKey) {
            ApiKeyCard(onOpenSettings = onOpenSettings)
        }

        LazyColumn(
            state = listState,
            // weight(1f) needs a bounded parent; in compact mode the sheet wraps its content, so
            // the list is allowed to size itself instead.
            modifier = if (compact) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.weight(1f).fillMaxWidth()
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty() && !state.needsApiKey) {
                item { IntroHint() }
            }
            items(state.messages) { message ->
                MessageBubble(message = message, onSpeak = { controller.speak(message.text) })
            }
            if (state.isSending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Thinking\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.messages.isEmpty() && state.suggestions.isNotEmpty()) {
            SuggestionRow(
                suggestions = state.suggestions,
                enabled = !state.needsApiKey && !state.isSending,
                onPick = { controller.ask(it.question) },
            )
        }

        InputRow(
            draft = draft,
            onDraftChange = { draft = it },
            onSend = { send() },
            onVoice = { startVoiceInput() },
            enabled = !state.needsApiKey && !state.isSending,
        )
    }
}



@Composable
private fun IntroHint() {
    Text(
        text = "Ask me anything about this walk, the city, its history, food or practicalities. " +
            "I am only here for travel questions.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ApiKeyCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Add your Gemini API key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The assistant needs a Google Gemini API key. It is free to create, and the " +
                    "free tier is generous for personal use. The tour itself keeps working without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenSettings) { Text("Open Settings") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionRow(
    suggestions: List<Suggestion>,
    enabled: Boolean,
    onPick: (Suggestion) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = "Try asking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // Wrapping pills rather than full-width bars: the questions are short, and stretching each
        // one across the screen left a lot of dead space and ate vertical room. Row spacing is kept
        // tight so the chips read as one group rather than as a list of buttons.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    // Deliberately not Surface's onClick overload: that enforces a 48dp minimum
                    // touch target, which is what kept these chips looking so far apart even after
                    // the padding was reduced. A plain clickable modifer lets them hug their text.
                    modifier = Modifier.clickable(enabled = enabled) { onPick(suggestion) },
                ) {
                    Text(
                        text = suggestion.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onSpeak: () -> Unit) {
    val isUser = message.role == ChatRole.USER
    val container = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = container,
            // Wraps its text, and only caps for long answers. Forcing a fraction of the width made
            // a two-word reply look like a full-width banner.
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
                if (!isUser && !message.isError) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onSpeak, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Read this answer aloud",
                                tint = content,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Listen",
                            style = MaterialTheme.typography.labelMedium,
                            color = content,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    enabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                // The field defaulted to 16sp bodyLarge, which was larger than every other piece of
                // text in the sheet, including the answers.
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text("Ask a question\u2026", style = MaterialTheme.typography.bodyMedium)
                },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onVoice, enabled = enabled) {
                Icon(Icons.Filled.Mic, contentDescription = "Ask by voice")
            }
            FilledIconButton(onClick = onSend, enabled = enabled && draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
