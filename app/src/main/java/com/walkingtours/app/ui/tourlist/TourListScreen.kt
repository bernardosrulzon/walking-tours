package com.walkingtours.app.ui.tourlist

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.walkingtours.app.data.db.TourEntity
import com.walkingtours.app.ui.components.AssetPhoto
import com.walkingtours.app.ui.components.MapsWarmUp
import com.walkingtours.app.ui.components.Pill
import com.walkingtours.app.util.Formatters

/**
 * Home screen: the list of available tours, each with the short description and the headline
 * numbers a walker needs to decide whether to start now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourListScreen(
    onOpenTour: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val repository = ServiceLocator.repository

    // Seed the bundled content the first time this screen appears. Room's flows then emit on their
    // own once the insert lands, so no manual refresh is needed.
    LaunchedEffect(Unit) { repository.ensureContentLoaded() }

    val tours by remember { repository.observeTours() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val stopCounts by remember { repository.observeStopCounts() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Where to warm the maps engine up (see MapsWarmUp): the first stop of the first tour. Anywhere
    // along a walk would do — every map here frames the same route — and this costs one indexed read
    // on a screen that is going to query the stops anyway.
    var warmUpAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(tours, stopCounts) {
        if (warmUpAt != null) return@LaunchedEffect
        val firstTour = tours.firstOrNull() ?: return@LaunchedEffect
        val firstStop = runCatching { repository.getStops(firstTour.id).firstOrNull() }
            .getOrNull() ?: return@LaunchedEffect
        warmUpAt = firstStop.lat to firstStop.lng
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Walking Tours", fontWeight = FontWeight.SemiBold)
                        Text(
                            "AI-guided audio walks",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Voice and AI settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        text = "Put your earphones in, pick a walk, and let the app tell you what you " +
                            "are looking at as you arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(tours, key = { it.id }) { tour ->
                    val stopCount = stopCounts.firstOrNull { it.tourId == tour.id }?.stopCount ?: 0
                    TourCard(tour = tour, stopCount = stopCount, onClick = { onOpenTour(tour.id) })
                }

                if (tours.isEmpty()) {
                    item {
                        Text(
                            text = "Loading tours\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

        // A single pixel of map, behind the list's corner. The walker cannot see it and does not
        // wait for it; the tour's own map is what benefits (see MapsWarmUp).
        MapsWarmUp(
            lat = warmUpAt?.first,
            lng = warmUpAt?.second,
            modifier = Modifier.align(Alignment.BottomEnd).padding(innerPadding),
        )
        }
    }
}

@Composable
private fun TourCard(tour: TourEntity, stopCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            AssetPhoto(
                assetPath = tour.heroImage,
                contentDescription = "${tour.city} skyline",
                modifier = Modifier.fillMaxWidth().height(170.dp),
            )
            // Scrim so the city name stays legible over a bright photograph.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0.55f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to androidx.compose.ui.graphics.Color(0xCC000000),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${tour.city}, ${tour.country}",
                    style = MaterialTheme.typography.titleSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Column(Modifier.padding(16.dp)) {
            Text(
                text = tour.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = tour.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("$stopCount stops", icon = Icons.Filled.Place)
                Pill("${tour.distanceKm} km", icon = Icons.AutoMirrored.Filled.DirectionsWalk)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Formatters.duration(tour.totalWalkMinutes) + " walking", icon = Icons.Filled.Schedule)
                Pill("Audio guided", icon = Icons.Filled.ConfirmationNumber)
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("View tour details")
            }
        }
    }
}

