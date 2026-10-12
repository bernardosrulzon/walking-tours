package com.walkingtours.app.ui.cities

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.ui.components.AssetPhoto
import com.walkingtours.app.util.CountryFlags

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
    val countries = tours.map { it.country }.distinct().sorted()
    // Both survive the trip to a city and back: the back-stack entry keeps them while the
    // city is on top, so returning lands exactly where the walker left off.
    var selectedCountry by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    var filterExpanded by remember { mutableStateOf(false) }
    var filterFieldWidthPx by remember { mutableStateOf(0) }
    val filterArrowRotation by animateFloatAsState(
        targetValue = if (filterExpanded) 180f else 0f,
        label = "filter-arrow",
    )
    val filterMenuScroll = rememberScrollState()
    val visible = if (selectedCountry == null) {
        cities
    } else {
        cities.filterValues { cityTours -> cityTours.firstOrNull()?.country == selectedCountry }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // One compact dropdown instead of a chip per country: at eighteen countries a chip
            // row is a scroll marathon, while the menu shows everything in one tap.
            if (countries.size > 1) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 4.dp)
                        .onSizeChanged { filterFieldWidthPx = it.width },
                ) {
                    val selectedFlag = selectedCountry?.let { CountryFlags.flag(it) }.orEmpty()
                    OutlinedCard(
                        onClick = { filterExpanded = true },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (selectedCountry == null) "🌍" else selectedFlag,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = selectedCountry ?: "All countries",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.graphicsLayer {
                                    rotationZ = filterArrowRotation
                                },
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = filterExpanded,
                        onDismissRequest = { filterExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.width(
                            with(LocalDensity.current) { filterFieldWidthPx.toDp() },
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 336.dp)
                                .verticalScroll(filterMenuScroll),
                        ) {
                        DropdownMenuItem(
                            text = { Text("🌍 All countries") },
                            onClick = {
                                selectedCountry = null
                                filterExpanded = false
                            },
                            trailingIcon = {
                                if (selectedCountry == null) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                        )
                        countries.forEach { country ->
                            val flag = CountryFlags.flag(country)
                            DropdownMenuItem(
                                text = { Text(if (flag.isEmpty()) country else "$flag $country") },
                                onClick = {
                                    selectedCountry = country
                                    filterExpanded = false
                                },
                                trailingIcon = {
                                    if (selectedCountry == country) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                        }
                    }
                }
            }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(visible.entries.toList(), key = { it.key }) { (city, cityTours) ->
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
                            // The flag leads the name: it is what separates Shenzhen from Suzhou at a
                            // glance on a list of grey-roofed skylines.
                            val flag = CountryFlags.flag(cityTours.firstOrNull()?.country.orEmpty())
                            Text(
                                text = if (flag.isEmpty()) city else "$flag $city",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
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
}
