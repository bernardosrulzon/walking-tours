package com.walkingtours.app.ui.cities

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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ui.components.AssetPhoto

/**
 * Entry level for a collection that grows beyond one walk: a card per city, each opening the list
 * of tours bundled under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitiesScreen(
    onOpenCity: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val repository = ServiceLocator.repository

    LaunchedEffect(Unit) { repository.ensureContentLoaded() }

    val tours by remember { repository.observeTours() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // Group the bundled tours into one card per city. The first tour's hero stands in for the
    // city, which is always right once a city's tours are photographed in the same place.
    val cities = tours.groupBy { it.city }.toSortedMap()

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Pick a city, then a walk. Put your earphones in and let the app tell you " +
                        "what you are looking at as you arrive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(cities.entries.toList(), key = { it.key }) { (city, cityTours) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCity(city) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(170.dp)) {
                        AssetPhoto(
                            assetPath = cityTours.firstOrNull()?.heroImage,
                            contentDescription = "$city skyline",
                            modifier = Modifier.fillMaxWidth().height(170.dp),
                        )
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
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = city,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (cityTours.size == 1) {
                                "1 tour: ${cityTours.first().title}"
                            } else {
                                "${cityTours.size} tours"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.ArrowForward,
                            contentDescription = "Open $city",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (cities.isEmpty()) {
                item {
                    Text(
                        text = "Loading tours…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
