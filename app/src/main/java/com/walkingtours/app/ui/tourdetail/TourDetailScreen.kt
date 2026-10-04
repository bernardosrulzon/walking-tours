package com.walkingtours.app.ui.tourdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.data.db.TourEntity
import com.walkingtours.app.ui.chat.AskBar
import com.walkingtours.app.ui.chat.ChatBottomSheet
import com.walkingtours.app.ui.components.AssetPhoto
import com.walkingtours.app.ui.components.Pill
import com.walkingtours.app.ui.components.SectionTitle
import com.walkingtours.app.ui.components.TourMap
import com.walkingtours.app.util.Formatters

/**
 * Everything a walker needs to decide whether to set off: the route on a map, how long it takes,
 * what it costs, and the full list of stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourDetailScreen(
    tourId: String,
    onBack: () -> Unit,
    onStartTour: () -> Unit,
    onOpenStop: (String) -> Unit,
    /** Opens the walk's introduction, which heads the route. */
    onOpenIntroduction: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val repository = ServiceLocator.repository

    val tour by remember(tourId) { repository.observeTour(tourId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val stops by remember(tourId) { repository.observeStops(tourId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by remember(tourId) { repository.observeStopProgress(tourId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

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
    val ticketed = stops.filter { !it.isFree }

    Scaffold(
        bottomBar = {
            AskBar(placeholder = "Ask about this tour", onClick = { showChat = true })
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
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item {
                TourMap(
                    stops = stops,
                    visitedIds = visited,
                    selectedStopId = null,
                    userLat = mapLat,
                    userLng = mapLng,
                    userHeading = compassHeading,
                    userAccuracyMeters = mapAccuracy,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                )
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = tourEntity?.title ?: "Loading\u2026",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = tourEntity?.summary.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("${stops.size} stops", icon = Icons.Filled.Place)
                        Pill(
                            "${tourEntity?.distanceKm ?: 0.0} km",
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(
                            Formatters.duration(tourEntity?.totalWalkMinutes ?: 0) + " walking",
                            icon = Icons.Filled.Schedule,
                        )
                        Pill(
                            Formatters.totalExperience(
                                tourEntity?.totalWalkMinutes ?: 0,
                                totalStopMinutes,
                            ) + " with visits",
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onStartTour, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Explore, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (visited.isEmpty()) "Start tour" else "Resume tour")
                    }
                }
            }

            // ---- Ticket summary -------------------------------------------------
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Tickets and entrance fees",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${stops.size - ticketed.size} of ${stops.size} stops are free. " +
                                "${ticketed.size} need a ticket.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Turkish museum prices change frequently, sometimes more than " +
                                "once a year. Treat every figure in this app as a guide and check " +
                                "at the gate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        if (ticketed.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            ticketed.forEach { stop ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${stop.order}. ${stop.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = stop.entranceFeeTry,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Practicalities -------------------------------------------------
            item {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("Know before you go")
                    tourEntity?.difficulty?.takeIf { it.isNotBlank() }?.let {
                        LabeledParagraph("Difficulty", it)
                    }
                    tourEntity?.bestTimeOfDay?.takeIf { it.isNotBlank() }?.let {
                        LabeledParagraph("Best time", it)
                    }
                }
            }

            // ---- Itinerary ------------------------------------------------------
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SectionTitle("The route, stop by stop")
                    Text(
                        text = "Tap any stop to read or listen to it, whether or not you are there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The introduction heads the route rather than hiding behind a toggle: it is the first
            // thing the walk says, and it is a page of the stop screen now, like any stop.
            if (tourEntity?.overviewText?.isNotBlank() == true) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable(onClick = onOpenIntroduction),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary,
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
            }

            items(stops, key = { it.id }) { stop ->
                StopRow(
                    stop = stop,
                    visited = stop.id in visited,
                    onClick = { onOpenStop(stop.id) },
                )
            }

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

@Composable
private fun StopRow(stop: StopEntity, visited: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                AssetPhoto(
                    assetPath = stop.photoAsset,
                    contentDescription = stop.name,
                    modifier = Modifier.size(64.dp),
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
                    )
                }
                Text(
                    text = stop.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("${stop.suggestedMinutes} min")
                    Pill(
                        text = stop.entranceFeeTry,
                        container = if (stop.isFree) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        },
                        contentColor = if (stop.isFree) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        },
                    )
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
