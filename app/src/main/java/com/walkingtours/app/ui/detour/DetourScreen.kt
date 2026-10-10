package com.walkingtours.app.ui.detour

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ai.DetourPhoto
import com.walkingtours.app.ai.DetourTopic
import com.walkingtours.app.ai.fetchDetourPhoto
import com.walkingtours.app.audio.NarrationState
import com.walkingtours.app.tour.detourUtteranceId
import com.walkingtours.app.ui.chat.AskBar
import com.walkingtours.app.ui.chat.ChatBottomSheet
import com.walkingtours.app.ui.components.NarrationTransport
import com.walkingtours.app.ui.components.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A detour: one generated deep-dive that is not a stop.
 *
 * It borrows the stop page's shape — photograph, transport, collapsible transcript — but there is
 * no map (a detour is about a country, not a coordinate), no visitor information, and no
 * previous/next: a detour is a single chapter with nowhere to step to. The words come from the
 * tour's guide persona, exactly like a stop's; leaving the page stops the audio only if the detour
 * is still the thing playing, and never touches the walk itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetourScreen(
    tourId: String,
    topicId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val session = ServiceLocator.session
    val guide = ServiceLocator.guide
    val narration by session.narration.progress.collectAsStateWithLifecycle()
    val loading by guide.loading.collectAsStateWithLifecycle()
    val detourError by session.detourError.collectAsStateWithLifecycle()

    var topics by remember { mutableStateOf<List<DetourTopic>?>(null) }
    LaunchedEffect(tourId) {
        topics = guide.suggestDetourTopics(tourId)
    }
    val topic = topics?.find { it.id == topicId }

    // The controller's own signature, so the effect below re-fires exactly when the cached
    // text would change: guide, interests, tone or language.
    val signature = guide.detourSignature(tourId)
    val detourKey = "detour|$topicId"
    // Read through the controller helper, not a local signature computation: it covers the
    // guideless case with the neutral signature, and the audio plays from the same lookup, so the
    // two can never disagree about what is ready.
    val text = guide.detourText(tourId, topicId)
    val isLoading = detourKey in loading
    val utteranceId = detourUtteranceId(topicId)
    val highlightApplies = narration.stopId == utteranceId
    val isPlaying = narration.state == NarrationState.PLAYING ||
        narration.state == NarrationState.PREPARING

    // Play once the topic resolves, and again if the guide changes underneath it; the guards make
    // this a single attempt per state, never a loop.
    LaunchedEffect(topic?.id, signature, text, isLoading, detourError) {
        val resolved = topic ?: return@LaunchedEffect
        if (text == null && !isLoading && detourError == null) {
            session.playDetour(tourId, resolved)
        }
    }

    val context = LocalContext.current
    var photo by remember(topicId) { mutableStateOf<DetourPhoto?>(null) }
    var photoDone by remember(topicId) { mutableStateOf(false) }
    LaunchedEffect(topic) {
        val resolved = topic ?: return@LaunchedEffect
        photo = fetchDetourPhoto(context, resolved.id, resolved.imageQuery)
        photoDone = true
    }

    var transcriptOpen by remember { mutableStateOf(true) }
    var showChat by remember { mutableStateOf(false) }
    if (showChat) {
        ChatBottomSheet(
            tourId = tourId,
            stopId = null,
            onDismiss = { showChat = false },
            onOpenSettings = onOpenSettings,
        )
    }

    fun leave() {
        // Stop the detour's audio on the way out, but only the detour's: the walk underneath keeps
        // whatever it was doing.
        if (session.narration.progress.value.stopId == utteranceId) session.stopNarration()
        onBack()
    }
    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = topic?.title ?: "Detour",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            AskBar(
                placeholder = "Ask about this detour",
                onClick = { showChat = true },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            DetourImage(photo = photo, contentDescription = topic?.title)

            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            ) {
                Text(
                    text = "Detour",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = topic?.title ?: "Loading detour\u2026",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                when {
                    topic == null -> LoadingDetour()
                    isLoading -> LoadingDetour()
                    detourError != null -> DetourError(
                        message = detourError.orEmpty(),
                        onRetry = { session.playDetour(tourId, topic) },
                    )
                    else -> NarrationTransport(
                        state = if (highlightApplies) narration.state else NarrationState.IDLE,
                        positionMs = if (highlightApplies) narration.positionMs else 0L,
                        durationMs = if (highlightApplies) narration.durationMs else 0L,
                        rate = narration.rate,
                        onPlayPause = {
                            if (isPlaying && highlightApplies) {
                                session.pauseNarration()
                            } else if (highlightApplies && narration.state == NarrationState.PAUSED) {
                                session.resumeNarration()
                            } else {
                                session.playDetour(tourId, topic)
                            }
                        },
                        onRewind = { session.skipNarrationBy(-15_000) },
                        onForward = { session.skipNarrationBy(15_000) },
                        onPrevious = {},
                        onNext = {},
                        showPrevNext = false,
                        onSeekFraction = { fraction ->
                            session.seekNarrationTo((fraction * narration.durationMs).toLong())
                        },
                        onRateChange = { session.setNarrationRate(it) },
                        message = if (highlightApplies) narration.message else null,
                    )
                }
            }

            if (!isLoading && detourError == null && text != null) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { transcriptOpen = !transcriptOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Transcript",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (transcriptOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (transcriptOpen) "Hide transcript" else "Show transcript",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (transcriptOpen) {
                        Spacer(Modifier.height(8.dp))
                        Transcript(text = text)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The detour's illustration: the fetched photograph, or an empty panel of the same size. */
@Composable
private fun DetourImage(
    photo: DetourPhoto?,
    contentDescription: String?,
    height: Dp = 240.dp,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(photo) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photo) {
        val file = photo?.file
        bitmap = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 1080 && bounds.outHeight / (sample * 2) >= 1080) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )?.asImageBitmap()
                }.getOrNull()
            }
        }
    }

    Box(modifier) {
        Box(Modifier.fillMaxWidth().height(height)) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LoadingDetour() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Loading detour\u2026",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetourError(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Try again")
            }
        }
    }
}

/**
 * The topic picker: up to five detours, or the reason there are none. A missing Gemini key is a
 * different state from a failed generation — one needs Settings, the other a retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetourTopicsSheet(
    tourId: String,
    onPick: (DetourTopic) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val guide = ServiceLocator.guide
    val aiSettings by ServiceLocator.aiSettings.state.collectAsStateWithLifecycle()
    var topics by remember { mutableStateOf<List<DetourTopic>?>(null) }
    var attempted by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }

    LaunchedEffect(tourId, retryKey) {
        topics = null
        attempted = false
        topics = guide.suggestDetourTopics(tourId)
        attempted = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // Scrollable: with up to five cards the content is taller than the sheet on small
        // screens, and a clipped card reads as an empty box. The scroll takes over past the
        // sheet's max height instead.
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Go on a detour",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Country-level context behind this walk — history, politics, culture and more, " +
                    "told by your guide.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            when {
                topics == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Dreaming up detours\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                topics!!.isEmpty() && !aiSettings.hasGeminiKey -> {
                    Text(
                        text = "Detours need a Gemini API key — they are generated, not written in advance. " +
                            "Add one in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Settings")
                    }
                }

                topics!!.isEmpty() -> {
                    Text(
                        text = "Couldn't dream up detours right now. Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { retryKey++ },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Try again")
                    }
                }

                else -> {
                    topics!!.forEach { topic ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable { onPick(topic) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    text = topic.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                if (topic.blurb.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = topic.blurb,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
