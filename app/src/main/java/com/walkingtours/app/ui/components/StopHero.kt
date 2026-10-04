package com.walkingtours.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.walkingtours.app.data.db.StopEntity
import kotlinx.coroutines.launch

/**
 * Photograph and map in one place, swipeable, with a toggle for people who would rather tap.
 *
 * Showing both stacked was wasting several hundred pixels of the most valuable area on the screen
 * for information the walker only needs one of at a time: either "what does it look like" or "where
 * is it, and where am I".
 */
@Composable
fun StopHero(
    stop: StopEntity?,
    stops: List<StopEntity>,
    /**
     * Shown when there is no current stop — during the city introduction, before the walk begins.
     *
     * The tour's own photograph is the honest thing to put there: it is what the walker just tapped
     * on to get here, and the alternative was an empty panel held for as long as the introduction
     * runs.
     */
    fallbackPhotoAsset: String? = null,
    visitedIds: Set<String>,
    userLat: Double?,
    userLng: Double?,
    userHeading: Float?,
    userAccuracyMeters: Float?,
    onOpenStop: (StopEntity) -> Unit,
    /** Stops the map camera should frame; null frames the whole route. */
    focusStops: List<StopEntity>? = null,
    modifier: Modifier = Modifier,
    heroHeight: Dp = 250.dp,
    /** 0 shows the photograph first, 1 shows the map first. */
    initialPage: Int = 0,
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxWidth().height(heroHeight)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Swiping the page is reserved for moving between stops, so the hero is switched with
            // the Photo / Map chips instead. Two horizontal gestures on one screen would be
            // ambiguous, and one of them silently wins.
            userScrollEnabled = false,
        ) { page ->
            if (page == 0) {
                Box(Modifier.fillMaxSize()) {
                    AssetPhoto(
                        assetPath = stop?.photoAsset ?: fallbackPhotoAsset,
                        contentDescription = stop?.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                // A pan or a pinch that begins on the map belongs to the map, not to the pager that
                // wraps this page. The map itself is the child here, so it has already handled the
                // event by the time this runs; claiming it at the Main pass then hides it from the
                // pager above, which is what stops a two-finger zoom being read as a page swipe.
                // Swipes that begin anywhere else on the page are untouched and still change stop.
                Box(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                // Only single-pointer drags are claimed, which is what stops the
                                // pager reading a one-finger pan as a page swipe.
                                //
                                // Two or more pointers mean a pinch, and a pinch belongs entirely to
                                // the map: claiming it here swallowed the second finger before the
                                // map ever saw it, so zoom did nothing. Multi-touch is left alone.
                                if (event.changes.size == 1) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                ) {
                    TourMap(
                    stops = stops,
                    visitedIds = visitedIds,
                    userLat = userLat,
                    userLng = userLng,
                    userHeading = userHeading,
                    userAccuracyMeters = userAccuracyMeters,
                    selectedStopId = stop?.id,
                    focusStops = focusStops,
                        onStopClick = onOpenStop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("Photo" to 0, "Map" to 1).forEach { (label, page) ->
                val selected = pagerState.currentPage == page
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) Color(0xE61B5E8C) else Color(0xB3000000),
                    onClick = { scope.launch { pagerState.animateScrollToPage(page) } },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
