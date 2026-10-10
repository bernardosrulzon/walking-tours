package com.walkingtours.app.ui.tourdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.data.db.TourEntity
import com.walkingtours.app.ui.chat.ChatBottomSheet
import com.walkingtours.app.ui.components.AssetPhoto
import com.walkingtours.app.ui.components.InfoCard
import com.walkingtours.app.ui.components.Pill
import com.walkingtours.app.ui.components.SectionTitle
import com.walkingtours.app.ui.components.TourMap
import com.walkingtours.app.ui.detour.DetourTopicsSheet
import com.walkingtours.app.util.Formatters

/**
 * Height of the overview map, and of the empty panel that holds its place until the tour loads.
 */
private val MAP_HEIGHT = 280.dp

/**
 * Everything a walker needs to decide whether to set off: the route on a map, how long it takes,
 * what it costs, and the full list of stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourDetailScreen(
    tourId: String,
    onBack: () -> Unit,
    /** Begins the walk: the introduction plays, then the geofences take over. */
    onStartTour: () -> Unit,
    /** Continues a part-finished walk at the first stop still to see. */
    onResumeTour: () -> Unit,
    /** Wipes completed stops and begins again. */
    onStartOver: () -> Unit,
    /** Starts the walk at this stop and shows it. */
    onOpenStop: (String) -> Unit,
    /** Opens a detour deep-dive on the chosen topic. */
    onOpenDetour: (String) -> Unit,
    /** Opens the persona flow to choose or change the guide. */
    onOpenPersona: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val repository = ServiceLocator.repository

    val tour by remember(tourId) { repository.observeTour(tourId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val stops by remember(tourId) { repository.observeStops(tourId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by remember(tourId) { repository.observeStopProgress(tourId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // The chosen guide, shown on the page so the walker can see who is talking and change it.
    val persona by ServiceLocator.personaSettings.state.collectAsStateWithLifecycle()
    val guide = persona.guide(tourId)

    // Detours are generated country-level context for this tour; the sheet lists them, and a pick
    // navigates to the deep-dive page.
    var showDetours by remember { mutableStateOf(false) }
    if (showDetours) {
        DetourTopicsSheet(
            tourId = tourId,
            onPick = { topic ->
                showDetours = false
                onOpenDetour(topic.id)
            },
            onOpenSettings = onOpenSettings,
            onDismiss = { showDetours = false },
        )
    }

    // The overview map shows the walker too. It used to be the one map in the app without a "you
    // are here", because only a running tour ever asked for position updates.
    val locationTracker = ServiceLocator.locationTracker
    val trackerFix by locationTracker.lastLocation.collectAsStateWithLifecycle(initialValue = null)
    val headingProvider = ServiceLocator.headingProvider
    val compassHeading by headingProvider.headingDegrees.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        locationTracker.acquire()
        headingProvider.start()
        onDispose {
            locationTracker.release()
            headingProvider.stop()
        }
    }
    val mapLat = trackerFix?.latitude
    val mapLng = trackerFix?.longitude
    val mapAccuracy = trackerFix?.accuracy

    // Same assistant, same interaction as the walking screens: a docked bar that opens the
    // conversation over the page, rather than a separate full-screen chat.
    var showChat by remember { mutableStateOf(false) }
    if (showChat) {
        ChatBottomSheet(
            tourId = tourId,
            stopId = null,
            onDismiss = { showChat = false },
            onOpenSettings = onOpenSettings,
        )
    }

    val tourEntity = tour
    val visited = progress.map { it.stopId }.toSet()
    val totalStopMinutes = stops.sumOf { it.suggestedMinutes }
    // Three states, one button: nothing done, part done, all done. Once every stop is completed
    // there is nothing left to resume to, so it offers to begin again.
    val allDone = stops.isNotEmpty() && stops.all { it.id in visited }
    val startAction = when {
        allDone -> onStartOver
        visited.isEmpty() -> onStartTour
        // Resume does not replay the introduction: the walker has already begun, and what they
        // want is the next stop still to see.
        else -> onResumeTour
    }
    val startLabel = when {
        allDone -> "Start over"
        visited.isEmpty() -> "Start tour"
        else -> "Resume tour"
    }
    val ticketed = stops.filter { !it.isFree }
    // When every ticketed stop shares one note — a tour covered by a single combined ticket — say
    // that once instead of repeating it down a list of stops.
    val sharedTicketNote = ticketed
        .map { it.entranceFeeNote.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .singleOrNull()

    Scaffold(
        bottomBar = {
            // Two fixed actions, thumb-sized: Ask opens the assistant sheet, Start begins or
            // resumes the walk. They live here rather than in the scrolling content so the
            // walker never has to scroll back up to set off.
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { showChat = true },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Ask", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(
                        onClick = startAction,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(
                            Icons.Filled.Explore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(startLabel, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(tourEntity?.city ?: "Tour", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        // The page scrolls without a lazy list, so that nothing on it leaves the composition
        // when it goes past — the map above all. A map inside a lazy list is disposed the moment
        // it scrolls off and built again on the way back, which costs a second of tiles and a
        // flash of nothing every time the walker looks down the stop list and up again.
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            TourMap(
                stops = stops,
                visitedIds = visited,
                selectedStopId = null,
                userLat = mapLat,
                userLng = mapLng,
                userHeading = compassHeading,
                userAccuracyMeters = mapAccuracy,
                modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT),
            )

            // Nothing else until the tour arrives. The map above already holds its own dimensions —
            // an empty panel when there is no route to frame yet — and a headline reading
            // "Loading…" over a screen of zeroes is a worse answer than an empty page that fills
            // in a moment later.
            if (tourEntity == null) return@Column

            Column(Modifier.padding(16.dp)) {
                Text(
                    text = tourEntity?.title ?: "Loading\u2026",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tourEntity?.summary.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill("${stops.size} stops", icon = Icons.Filled.Place)
                    Pill(
                        "${tourEntity?.distanceKm ?: 0.0} km",
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    )
                    Pill(
                        Formatters.totalExperience(
                            tourEntity?.totalWalkMinutes ?: 0,
                            totalStopMinutes,
                        ) + " with visits",
                        icon = Icons.Filled.Schedule,
                    )
                }

                Spacer(Modifier.height(16.dp))
                // Who is telling the story, and the way to change them. Choosing happens
                // automatically on the first Start tour; this is the door back to it. Outlined,
                // not filled: info panels never compete with the tappable cards below.
                InfoCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Explore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Your guide",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = guide?.name ?: "Not chosen yet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            guide?.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                                Text(
                                    text = tagline,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                        TextButton(onClick = onOpenPersona) {
                            Text(if (guide == null) "Choose" else "Change")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // A way off the route: generated deep-dives into the country behind the walk —
                // history, politics, culture — told by the same guide, with no walking required.
                InfoCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Explore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Go on a detour",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "The history, politics and culture behind this walk",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { showDetours = true }) {
                            Text("Explore")
                        }
                    }
                }
            }

            // ---- Ticket summary -------------------------------------------------
            // Outlined like the guide/detour panels: a long ticket list must not become a wall
            // of colour. The tint lives in the icon box only.
            InfoCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Tickets and entrance fees",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (sharedTicketNote != null) {
                            "${stops.size - ticketed.size} of ${stops.size} stops are free; one " +
                                "ticket covers the rest."
                        } else {
                            "${stops.size - ticketed.size} of ${stops.size} stops are free. " +
                                "${ticketed.size} need a ticket."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Turkish museum prices change frequently, sometimes more than " +
                            "once a year. Treat every figure in this app as a guide and check " +
                            "at the gate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (ticketed.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        if (sharedTicketNote != null) {
                            Text(
                                text = sharedTicketNote,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        } else {
                            ticketed.forEach { stop ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${stop.order}. ${stop.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = stop.entranceFeeTry,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.End,
                                        // Cap the fee column so a long price string cannot squeeze
                                        // the stop name down to one word per line.
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Practicalities -------------------------------------------------
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Know before you go")
                tourEntity?.difficulty?.takeIf { it.isNotBlank() }?.let {
                    LabeledParagraph("Difficulty", it)
                }
                tourEntity?.bestTimeOfDay?.takeIf { it.isNotBlank() }?.let {
                    LabeledParagraph("Best time", it)
                }
            }

            // ---- Itinerary ------------------------------------------------------
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SectionTitle("The route, stop by stop")
                Text(
                    text = "The walk starts with the introduction. Tap any stop to begin there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The introduction heads the route rather than hiding behind a toggle: it is the first
            // page of the walk, and tapping it is the same act as pressing Start tour.
            if (tourEntity?.overviewText?.isNotBlank() == true) {
                Card(
                    onClick = onStartTour,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Introduction", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Before you set off",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            stops.forEach { stop ->
                StopRow(
                    stop = stop,
                    visited = stop.id in visited,
                    onClick = { onOpenStop(stop.id) },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LabeledParagraph(label: String, value: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopRow(stop: StopEntity, visited: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(16.dp),
                    ),
            ) {
                AssetPhoto(
                    assetPath = stop.photoAsset,
                    contentDescription = stop.name,
                    modifier = Modifier.size(56.dp),
                    targetWidthPx = 240,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${stop.order}.",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stop.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stop.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("${stop.suggestedMinutes} min")
                    // Covered stops have no fee of their own; the tour's ticket card says so once.
                    if (stop.entranceFeeTry.isNotBlank()) {
                        if (stop.isFree) {
                            Pill(
                                text = stop.entranceFeeTry,
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            Pill(text = stop.entranceFeeTry)
                        }
                    }
                }
            }
            if (visited) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Visited",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
