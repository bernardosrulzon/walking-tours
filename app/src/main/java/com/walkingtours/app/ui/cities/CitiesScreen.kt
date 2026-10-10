package com.walkingtours.app.ui.cities

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
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
                    onClick = { onOpenCity(city) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Box(Modifier.fillMaxWidth().height(180.dp)) {
                        AssetPhoto(
                            assetPath = cityTours.firstOrNull()?.heroImage,
                            contentDescription = "$city skyline",
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp)
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
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = city,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                // Single-tour cities name the walk right here; multi-tour cities
                                // list their walks one level down, so there is nothing to count.
                                if (cityTours.size == 1) {
                                    Text(
                                        text = cityTours.first().title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.88f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.18f),
                                        androidx.compose.foundation.shape.CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.ArrowForward,
                                    contentDescription = "Open $city",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
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
