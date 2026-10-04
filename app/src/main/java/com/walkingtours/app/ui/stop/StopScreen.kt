package com.walkingtours.app.ui.stop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.audio.NarrationState
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.tour.TourSessionManager
import com.walkingtours.app.ui.chat.AskBar
import com.walkingtours.app.ui.chat.ChatBottomSheet
import com.walkingtours.app.ui.components.ArrivalBanner
import com.walkingtours.app.ui.components.InfoRow
import com.walkingtours.app.ui.components.NarrationTransport
import com.walkingtours.app.ui.components.Pill
import com.walkingtours.app.ui.components.SectionTitle
import com.walkingtours.app.ui.components.StopHero
import com.walkingtours.app.ui.components.Transcript
import com.walkingtours.app.ui.components.VisitedCheck
import com.walkingtours.app.util.Formatters
import com.walkingtours.app.util.Geo
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The one screen for looking at a stop — whether you tapped it from the tour overview, or arrived
 * at it by walking.
 *
 * There used to be two screens here, and they overlapped enough to feel like duplicates: both showed
 * a photograph and a map, both showed the transcript, and both had playback controls. They differed
 * only in that one knew a tour was in progress. So there is now a single page, and the walking-only
 * parts — the arrival banner, the walk-to-the-next-stop guidance, tour progress and the End action —
 * simply appear when a tour is running.
 *
 * A null [stopId] means "resume the tour and show me wherever I am".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopScreen(
    tourId: String,
    /** Null means "resume the tour and show me wherever I am". */
    stopId: String?,
    startAtStopId: String?,
    onBack: () -> Unit,
    /** Called with another stop to open — one tapped on the hero's map, for instance. */
    onOpenStop: (String) -> Unit,
    onStartTour: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val session = ServiceLocator.session
    val repository = ServiceLocator.repository
    val state by session.state.collectAsStateWithLifecycle()
    val narration by session.narration.progress.collectAsStateWithLifecycle()

    // The compass only matters while this screen is visible, so it is bound to its lifetime.
    val headingProvider = ServiceLocator.headingProvider
    val compassHeading by headingProvider.headingDegrees.collectAsStateWithLifecycle()

    // Position updates are reference counted, so holding a lease here means the map shows the walker
    // while simply browsing a stop, and releasing it cannot stop a running tour's geofences.
    val locationTracker = ServiceLocator.locationTracker
    val trackerFix by locationTracker.lastLocation.collectAsStateWithLifecycle(initialValue = null)
    DisposableEffect(Unit) {
        headingProvider.start()
        locationTracker.acquire()
        onDispose {
            headingProvider.stop()
            locationTracker.release()
        }
    }

    val isResume = stopId == null

    // "Resume" means: make sure the tour is running, then follow the session's current stop.
    LaunchedEffect(tourId, startAtStopId, isResume) {
        if (isResume && (state.tourId != tourId || !state.isRunning)) {
            session.startTour(tourId, startAtStopId)
        }
    }

    val allStops by produceState(initialValue = emptyList<StopEntity>(), tourId) {
        repository.ensureContentLoaded()
        value = repository.getStops(tourId)
    }
    val stopProgress by remember(tourId) { repository.observeStopProgress(tourId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val visitedIds = stopProgress.map { it.stopId }.toSet()

    // Prefer the tracker's own fix: the session only carries one while a tour is running.
    val mapLat = trackerFix?.latitude ?: state.userLat
    val mapLng = trackerFix?.longitude ?: state.userLng
    val mapAccuracy = trackerFix?.accuracy ?: state.userAccuracyMeters

    val isLive = state.isRunning && state.tourId == tourId

    // A pager rather than a sideways gesture that only navigates on release, because the neighbouring
    // stop has to be visible *while the finger is still down* — the next page follows the drag and
    // then settles, which is what makes moving between stops feel like one continuous surface.
    // Navigation Compose has no gesture-driven transition API, so a pager is the only way to get
    // that, and the pages are the stops themselves.
    val pagerState = rememberPagerState(pageCount = { allStops.size })
    val scope = rememberCoroutineScope()

    // The top bar and the AskBar follow the settled page, not whichever stop the screen was opened
    // with, so the title always names the page the walker has landed on.
    //
    // Until the tour names a stop — the city introduction is playing — there is no stop to name
    // either: a pager has no "no stop" page, so the pages below are held empty to match.
    val noStopYet = isResume && state.currentStopId == null
    val settledStop = allStops.getOrNull(pagerState.currentPage).takeUnless { noStopYet }

    // Land on the stop we were asked for as soon as the list has arrived: the stop that was opened,
    // else wherever the tour has got to, else the first page. It happens once — after that the
    // walker's own swiping is in charge — and while resuming it waits for the session to name a
    // stop rather than yanking the pager to the first page and staying there.
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(allStops, stopId, state.currentStopId) {
        if (didInitialScroll || allStops.isEmpty() || pagerState.isScrollInProgress) {
            return@LaunchedEffect
        }
        val wanted = stopId ?: state.currentStopId ?: return@LaunchedEffect
        val target = allStops.indexOfFirst { it.id == wanted }
        if (target < 0) return@LaunchedEffect
        didInitialScroll = true
        if (target != pagerState.currentPage) pagerState.scrollToPage(target)
    }

    // On the resume route the screen belongs to the session: when the walker reaches the next stop
    // the pager has to follow it, which is what resolving the session's stop used to do. A stop that
    // was opened deliberately is the walker's to leave, so only the resume route follows.
    LaunchedEffect(isResume, allStops) {
        if (!isResume) return@LaunchedEffect
        snapshotFlow { state.currentStopId }.collect { id ->
            if (!didInitialScroll) return@collect
            val target = allStops.indexOfFirst { it.id == id }
            if (target >= 0 && target != pagerState.currentPage) {
                pagerState.animateScrollToPage(target)
            }
        }
    }

    // When a tour is running the narration follows the page the walker settles on, so the audio
    // matches the stop being read. session.state.value is read here rather than the collected
    // snapshot, and compared with the page's own stop, so a page that is already the current stop is
    // left alone instead of having its narration restarted.
    LaunchedEffect(isLive) {
        snapshotFlow { pagerState.settledPage }
            // The page the screen opens on is the screen arriving, not the walker moving.
            .drop(1)
            .collect { page ->
                val id = allStops.getOrNull(page)?.id ?: return@collect
                if (isLive && session.state.value.currentStopId != id) session.playStop(id)
            }
    }

    // Every previous/next control drives the pager instead of the navigator, so the neighbour slides
    // in under the same gesture the walker's finger uses.
    fun moveToPage(target: Int) {
        if (target in allStops.indices) scope.launch { pagerState.animateScrollToPage(target) }
    }

    val overviewPlaying = narration.stopId == TourSessionManager.OVERVIEW_ID
    val isPlaying = narration.state == NarrationState.PLAYING ||
        narration.state == NarrationState.PREPARING

    /**
     * Leaving a stop page stops the narration.
     *
     * Walking away from the tour is a decision to stop listening, and leaving it talking to an empty
     * pocket is worse than stopping a fraction too eagerly. Putting the app in the background is the
     * deliberate exception — that keeps playing, because that is what you do when you pocket the
     * phone mid-walk.
     */
    val leaveStop: () -> Unit = {
        if (isLive) session.endTour()
        onBack()
    }

    // The system back button has to do the same as the toolbar arrow, which NavHost would otherwise
    // pop on its own without stopping anything.
    BackHandler(enabled = isLive) { leaveStop() }

    var showChat by remember { mutableStateOf(false) }
    var showStopList by remember { mutableStateOf(false) }

    if (showChat) {
        ChatBottomSheet(
            tourId = tourId,
            stopId = settledStop?.id,
            onDismiss = { showChat = false },
            onOpenSettings = onOpenSettings,
        )
    }
    if (showStopList) {
        StopListSheet(
            stops = allStops,
            visitedIds = visitedIds,
            currentStopId = state.currentStopId,
            nextStopId = state.nextStopId,
            onPick = { picked ->
                showStopList = false
                // Jumping around the list slides the pager across rather than pushing a new screen;
                // the settled page is what makes the stop current while a tour is running.
                val pickedIndex = allStops.indexOfFirst { it.id == picked }
                if (pickedIndex >= 0) moveToPage(pickedIndex)
            },
            onDismiss = { showStopList = false },
        )
    }

    /** Play this stop: advance the tour when one is running, otherwise just audition it. */
    fun playThisStop(id: String) {
        if (isLive) session.playStop(id) else session.previewStop(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = settledStop?.name ?: "Walking tour",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = when {
                                isLive && state.stops.isNotEmpty() ->
                                    "Walking \u00b7 ${state.visitedStopIds.size} of ${state.stops.size} reached"

                                allStops.isNotEmpty() && !noStopYet ->
                                    "Stop ${pagerState.currentPage + 1} of ${allStops.size}"

                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = leaveStop) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showStopList = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "All stops")
                    }
                },
            )
        },
        bottomBar = {
            AskBar(
                placeholder = settledStop?.let { "Ask about ${it.name}" } ?: "Ask about this walk",
                onClick = { showChat = true },
            )
        },
    ) { innerPadding ->
        if (allStops.isEmpty()) {
            // There are no pages to page through until the stops arrive, and the old screen showed
            // the same line while it waited.
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)) {
                Text(
                    text = "Getting your tour ready\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) { page ->
                // Everything this page shows comes from its own index: the stop itself, whether that
                // stop is the tour's current one, whether the narration highlighting belongs to it,
                // and the neighbours it can move to.
                val pageStop = allStops.getOrNull(page).takeUnless { noStopYet }
                val pageIndex = page
                val pageStopId = pageStop?.id
                val isCurrentStop = pageStopId != null && pageStopId == state.currentStopId
                val highlightApplies = isCurrentStop && narration.stopId == pageStopId
                val previous = allStops.getOrNull(pageIndex - 1)
                val nextInRoute = allStops.getOrNull(pageIndex + 1)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    if (isLive) {
                        item {
                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    state.locationIssue?.let { issue ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = issue,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    // The arrival banner only exists while walking, and only for the stop just reached.
                    if (state.arrivedStopId != null && state.arrivedStopId == pageStop?.id) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                ArrivalBanner(
                                    stopName = state.arrivedStop?.name.orEmpty(),
                                    onDismiss = { session.acknowledgeArrival() },
                                )
                            }
                        }
                    }

                    item {
                        StopHero(
                            stop = pageStop,
                            stops = if (isLive) state.stops else allStops,
                            visitedIds = visitedIds,
                            userLat = mapLat,
                            userLng = mapLng,
                            userHeading = compassHeading ?: state.userBearingDegrees,
                            userAccuracyMeters = mapAccuracy,
                            onOpenStop = { onOpenStop(it.id) },
                            // Frame this stop and the next one rather than the entire route: the useful
                            // question on a stop page is "where do I go next", not "where does this walk
                            // go in total".
                            focusStops = listOfNotNull(pageStop, nextInRoute),
                            heroHeight = 240.dp,
                            // Walking: the map is what you need. Browsing: the photograph is.
                            initialPage = if (isLive) 1 else 0,
                        )
                    }

                    // ---- The city introduction, while it plays -------------------------------------
                    if (state.showingOverview && state.overviewText.isNotBlank()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Before you set off",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Introduction to the walk",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    NarrationTransport(
                                        state = if (overviewPlaying) narration.state else NarrationState.IDLE,
                                        positionMs = if (overviewPlaying) narration.positionMs else 0L,
                                        durationMs = if (overviewPlaying) narration.durationMs else 0L,
                                        rate = narration.rate,
                                        onPlayPause = {
                                            if (overviewPlaying && isPlaying) {
                                                session.pauseNarration()
                                            } else if (overviewPlaying && narration.state == NarrationState.PAUSED) {
                                                session.resumeNarration()
                                            } else {
                                                session.playOverview()
                                            }
                                        },
                                        onRewind = { session.skipNarrationBy(-15_000) },
                                        onForward = { session.skipNarrationBy(15_000) },
                                        onPrevious = {},
                                        onNext = {
                                            // The transport's next is the pager's next page: moving on
                                            // slides the neighbouring stop in rather than pushing a screen.
                                            val target = state.nextStop
                                            val targetPage = target?.let { next ->
                                                allStops.indexOfFirst { it.id == next.id }
                                            } ?: -1
                                            if (targetPage >= 0) moveToPage(targetPage)
                                        },
                                        onSeekFraction = { fraction ->
                                            session.seekNarrationTo((fraction * narration.durationMs).toLong())
                                        },
                                        onRateChange = { session.setNarrationRate(it) },
                                        message = if (overviewPlaying) narration.message else null,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Transcript(
                                        text = state.overviewText,
                                        highlightStart = if (overviewPlaying) narration.highlightStart else 0,
                                        highlightEnd = if (overviewPlaying) narration.highlightEnd else 0,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }

                    // ---- This stop ------------------------------------------------------------------
                    pageStop?.let { current ->
                        item {
                            Column(
                                Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    // A little air under the photograph or map, so the title reads as a
                                    // separate block rather than as a caption printed on the image.
                                    top = 20.dp,
                                ),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Ticking this marks the stop as reached, which is what the "I am here
                                    // now" button used to do — but it also works before the trip, when you
                                    // are reading ahead rather than standing in front of the thing.
                                    VisitedCheck(
                                        checked = current.id in visitedIds,
                                        onCheckedChange = { session.setStopVisited(tourId, current.id, it) },
                                        number = current.order,
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = current.category,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = current.name,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                NarrationTransport(
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
                                            playThisStop(current.id)
                                        }
                                    },
                                    onRewind = { session.skipNarrationBy(-15_000) },
                                    onForward = { session.skipNarrationBy(15_000) },
                                    onPrevious = { moveToPage(pageIndex - 1) },
                                    onNext = { moveToPage(pageIndex + 1) },
                                    onSeekFraction = { fraction ->
                                        session.seekNarrationTo((fraction * narration.durationMs).toLong())
                                    },
                                    onRateChange = { session.setNarrationRate(it) },
                                    message = if (highlightApplies) narration.message else null,
                                )
                            }
                        }

                        item {
                            Column(Modifier.padding(16.dp)) {
                                SectionTitle("Transcript")
                                Transcript(
                                    text = current.narration,
                                    highlightStart = if (highlightApplies) narration.highlightStart else 0,
                                    highlightEnd = if (highlightApplies) narration.highlightEnd else 0,
                                )
                            }
                        }

                        // Visitor information: the reference material that makes this the stop page.
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    SectionTitle("Visitor information")
                                    InfoRow(
                                        icon = Icons.Filled.ConfirmationNumber,
                                        label = "Entrance fee",
                                        value = buildString {
                                            append(current.entranceFeeTry)
                                            if (current.entranceFeeNote.isNotBlank()) {
                                                append("\n${current.entranceFeeNote}")
                                            }
                                        },
                                    )
                                    InfoRow(
                                        icon = Icons.Filled.Schedule,
                                        label = "Opening hours",
                                        value = current.openingHours,
                                    )
                                    InfoRow(
                                        icon = Icons.Filled.Place,
                                        label = "Suggested time here",
                                        value = "${current.suggestedMinutes} minutes",
                                    )
                                    InfoRow(
                                        icon = Icons.Filled.Info,
                                        label = "Accessibility",
                                        value = current.accessibility,
                                    )
                                }
                            }
                        }

                        current.insiderTip.takeIf { it.isNotBlank() }?.let { tip ->
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    ),
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Insider tip",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = tip,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                }
                            }
                        }

                        // Onward directions exist twice over: the curated text from the content, and the
                        // live distance when we actually know where the walker is.
                        current.nextStopDirections.takeIf { it.isNotBlank() }?.let { directions ->
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ),
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.DirectionsWalk,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (nextInRoute != null) {
                                                    if (isLive) "Walk to stop ${nextInRoute.order}" else "On to ${nextInRoute.name}"
                                                } else {
                                                    "Finishing the tour"
                                                },
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                        if (isLive && isCurrentStop) {
                                            val distance = state.distanceToNextMeters
                                            val bearing = state.bearingToNextDegrees
                                            if (distance != null && bearing != null) {
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    text = "${Geo.formatDistance(distance)} away to the " +
                                                        "${Geo.compassDirection(bearing)}, about " +
                                                        "${Formatters.duration(Geo.walkingMinutes(distance))} on foot",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = directions,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Column(Modifier.padding(16.dp)) {
                                if (!isLive) {
                                    Button(
                                        onClick = { onStartTour(current.id) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Start the tour from here")
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Begins straight away at ${current.name}, with no need to walk " +
                                            "back to the first stop. Stops play automatically as you walk " +
                                            "into them.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = { moveToPage(pageIndex - 1) },
                                        enabled = previous != null,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Previous", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { moveToPage(pageIndex + 1) },
                                        enabled = nextInRoute != null,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Next", maxLines = 1)
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }

                            }
                        }
                    }

                    // A page with no stop is either still loading, or the introduction is playing, which is
                    // the one state that has no stop to show.
                    if (pageStop == null) {
                        item {
                            Column(Modifier.padding(24.dp)) {
                                Text(
                                    text = "Getting your tour ready\u2026",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The jump list, so any stop can be reached from anywhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopListSheet(
    stops: List<StopEntity>,
    visitedIds: Set<String>,
    currentStopId: String?,
    nextStopId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "All stops",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            LazyColumn {
                items(stops.size) { i ->
                    val stop = stops[i]
                    val container = when {
                        stop.id == currentStopId -> MaterialTheme.colorScheme.primaryContainer
                        stop.id == nextStopId -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp)
                            .clickable { onPick(stop.id) },
                        colors = CardDefaults.cardColors(containerColor = container),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        if (stop.id in visitedIds) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        RoundedCornerShape(50),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stop.order.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stop.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stop.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (stop.id in visitedIds) {
                                Pill("seen")
                            }
                        }
                    }
                }
            }
        }
    }
}
