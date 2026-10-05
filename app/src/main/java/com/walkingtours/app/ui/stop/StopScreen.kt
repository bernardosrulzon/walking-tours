package com.walkingtours.app.ui.stop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkingtours.app.ServiceLocator
import com.walkingtours.app.audio.NarrationState
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.tour.TourEntry
import com.walkingtours.app.tour.TourSessionManager
import com.walkingtours.app.ui.chat.AskBar
import com.walkingtours.app.ui.chat.ChatBottomSheet
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
 * Height of the hero every page carries, and of the empty panel that holds its place before a page
 * is ready. One value, because the two have to match: the panel is there so that nothing moves when
 * the real thing arrives.
 */
private val HERO_HEIGHT = 240.dp

/**
 * The one screen for walking and for looking at a stop.
 *
 * A tour is one introduction plus N stops, and this screen shows them as a single pager: page zero is
 * the introduction when the tour has one, and the stops follow. The [entry] the screen arrived with
 * decides where it lands; after that the session's current stop is the only authority on which page
 * is showing. Swiping to a page makes that stop current, and a geofence arrival moves the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopScreen(
    tourId: String,
    /** Which page to land on: the introduction, the first stop still to see, or one stop. */
    entry: TourEntry,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val session = ServiceLocator.session
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

    // One entry, one start. Every way in — Start tour, Start over, Resume, a tapped stop — arrives
    // here, and the session turns it into a landing page. Re-applying it after a configuration
    // change is safe: the session adjusts an already-running tour instead of restarting it.
    LaunchedEffect(tourId, entry) {
        session.startTour(tourId, entry)
    }

    // Prefer the tracker's own fix: the session only carries one while a tour is running.
    val mapLat = trackerFix?.latitude ?: state.userLat
    val mapLng = trackerFix?.longitude ?: state.userLng
    val mapAccuracy = trackerFix?.accuracy ?: state.userAccuracyMeters

    val isLive = state.isRunning && state.tourId == tourId

    // Every page this screen shows comes from the session, and only while the session is running this
    // tour: the state carries whichever tour was started last, which is not necessarily this one.
    // Reading the repository's own copy instead used to compose a page before the session had named a
    // stop, so tapping a stop flashed the introduction — page zero — for as long as the two sources
    // disagreed.
    val pageStops = if (isLive) state.stops else emptyList()
    val visitedIds = if (isLive) state.visitedStopIds else emptySet()

    // The introduction is a page in its own right, in front of the stops, so it takes an index of its
    // own and every page-to-stop mapping goes through stopIndexFor. It exists for as long as the walk
    // does — keyed on the tour's overview text, not on `state.showingOverview`, which turns false the
    // moment the walker moves on while the page stays, so stop one's Previous can lead back to it.
    val introPages = if (isLive && state.overviewText.isNotBlank()) 1 else 0
    fun stopIndexFor(page: Int) = page - introPages
    fun pageForStop(stopId: String): Int? =
        pageStops.indexOfFirst { it.id == stopId }.takeIf { it >= 0 }?.plus(introPages)

    // A pager rather than a sideways gesture that only navigates on release, because the neighbouring
    // stop has to be visible *while the finger is still down* — the next page follows the drag and
    // then settles, which is what makes moving between stops feel like one continuous surface.
    // Navigation Compose has no gesture-driven transition API, so a pager is the only way to get
    // that, and the pages are the stops themselves with the introduction in front of them.
    val pagerState = rememberPagerState(pageCount = { pageStops.size + introPages })
    val scope = rememberCoroutineScope()

    // One scroll position per page, held out here rather than inside each page so the introduction's
    // Next can put stop one back at the top on its way in.
    //
    // ScrollState rather than a lazy list's: the page scrolls, but nothing on it may be taken out of
    // the composition when it goes past. The map is the reason. A map inside a lazy list is disposed
    // the moment it scrolls off and built again on the way back, which costs a second of tiles and a
    // flash of nothing every time the walker looks down at the transcript and up again.
    val pageScrollStates = remember(pageStops.size, introPages) {
        List(pageStops.size + introPages) { ScrollState(0) }
    }

    // True once the pager has been put on the page the walker asked for. Until then it is still on
    // page zero, which is the introduction, so it is neither drawn nor allowed to name the screen.
    var landingApplied by remember { mutableStateOf(false) }

    // The top bar and the AskBar follow the settled page, so the title names the page the walker has
    // landed on. Before that landing the pager is still on page zero, so the title follows the stop
    // the session is taking them to instead — naming the introduction over a stop the walker has just
    // tapped is the same flash, just in the title. A negative stop index is the introduction, which
    // has no stop to name.
    val settledStopIndex = if (landingApplied) {
        stopIndexFor(pagerState.currentPage)
    } else {
        state.currentStopId?.let { pageForStop(it)?.minus(introPages) } ?: -1
    }
    val settledStop = pageStops.getOrNull(settledStopIndex)

    // The pager follows the session: the stop the tour is on is the page on screen. The first move is
    // a jump — the walker asked to land somewhere and should not watch the pages scroll past — and
    // every move after that is an animation, so a geofence arrival slides in.
    //
    // This is the only effect that moves the page by itself. Movement in the other direction — a page
    // settling makes its stop current — goes through playStop below, and the two cannot fight because
    // arriving at a page whose stop is already current does nothing.
    LaunchedEffect(state.currentStopId, isLive, pageStops, introPages) {
        if (!isLive || pageStops.isEmpty()) return@LaunchedEffect
        val current = state.currentStopId
        val target = when {
            // A null current stop is the introduction, which only has a page when the tour has one.
            current == null -> if (introPages > 0) 0 else return@LaunchedEffect
            // A stop the pages do not have yet: wait for the next state rather than guessing.
            else -> pageForStop(current) ?: return@LaunchedEffect
        }
        when {
            target == pagerState.currentPage -> landingApplied = true
            landingApplied -> pagerState.animateScrollToPage(target)
            else -> {
                pagerState.scrollToPage(target)
                landingApplied = true
            }
        }
    }

    // A page settling is what makes its stop current, so the audio and the page stay in step without
    // either steering the other. The page the screen opens on is the screen arriving rather than the
    // walker moving, so it is dropped: the landing above is what starts that narration.
    LaunchedEffect(isLive, pageStops, introPages) {
        if (!isLive) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .drop(1)
            .collect { page ->
                // A negative stop index is the introduction, which has no narration of its own.
                val id = pageStops.getOrNull(stopIndexFor(page))?.id ?: return@collect
                if (session.state.value.currentStopId != id) session.playStop(id)
            }
    }

    // Every previous/next control drives the pager instead of the navigator, so the neighbour slides
    // in under the same gesture the walker's finger uses. Targets are page numbers, not stop
    // numbers: the introduction occupies one of them.
    fun moveToPage(target: Int) {
        if (target in 0 until pagerState.pageCount) scope.launch { pagerState.animateScrollToPage(target) }
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
            stops = pageStops,
            visitedIds = visitedIds,
            currentStopId = state.currentStopId,
            nextStopId = state.nextStopId,
            introShown = introPages > 0,
            introSelected = settledStopIndex < 0,
            onPickIntro = {
                showStopList = false
                moveToPage(0)
            },
            onPick = { picked ->
                showStopList = false
                // Jumping around the list slides the pager across; the settled page is what makes the
                // stop current.
                pageForStop(picked)?.let { moveToPage(it) }
            },
            onDismiss = { showStopList = false },
        )
    }

    /** Play the stop on screen: the page is the stop, so this is the tour moving to it. */
    fun playThisStop(id: String) = session.playStop(id)

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
                                // The introduction is a page of its own and has no stop number to
                                // quote: "Stop 1 of 14" here would name a stop the walker is not
                                // looking at.
                                settledStopIndex < 0 -> "Introduction"

                                isLive && pageStops.isNotEmpty() ->
                                    "Walking \u00b7 ${state.visitedStopIds.size} of ${pageStops.size} reached"

                                pageStops.isNotEmpty() ->
                                    "Stop ${settledStopIndex + 1} of ${pageStops.size}"

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
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // The pager is composed even before it is on the right page, because the landing is a
            // scroll and a scroll needs a laid-out pager to move. It is simply not drawn until the
            // landing has been applied: page zero is the introduction, and showing that under a walker
            // who tapped stop nine is the flash this screen used to have.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (landingApplied) 1f else 0f),
            ) { page ->
                // Everything this page shows comes from the stop it maps to: the stop itself, whether
                // that stop is the tour's current one, whether the narration highlighting belongs to
                // it, and the neighbours it can move to. The introduction page — page zero when the
                // tour has one — maps to no stop at all and shows the introduction instead.
                val stopIndex = stopIndexFor(page)
                val pageStop = pageStops.getOrNull(stopIndex)
                val pageStopId = pageStop?.id
                val isCurrentStop = pageStopId != null && pageStopId == state.currentStopId
                val highlightApplies = isCurrentStop && narration.stopId == pageStopId
                val previousPage = page - 1
                val nextInRoute = pageStops.getOrNull(stopIndex + 1)

                // The page keeps its scroll position for as long as the screen is open; the
                // introduction's Next reaches into the page it opens to start it at the top.
                val scrollState = pageScrollStates[page]

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    state.locationIssue?.let { issue ->
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

                    StopHero(
                        stop = pageStop,
                        stops = pageStops,
                        fallbackPhotoAsset = state.overviewImage,
                        visitedIds = visitedIds,
                        userLat = mapLat,
                        userLng = mapLng,
                        userHeading = compassHeading ?: state.userBearingDegrees,
                        userAccuracyMeters = mapAccuracy,
                        // Tapping a stop on the map is the same move as swiping to its page; the
                        // settled page is what makes it current.
                        onOpenStop = { tapped -> pageForStop(tapped.id)?.let { moveToPage(it) } },
                        // Frame this stop and the next one rather than the entire route: the useful
                        // question on a stop page is "where do I go next", not "where does this walk
                        // go in total".
                        // The whole route while the introduction is what is showing: there is no
                        // "next stop" yet, and the tour as a whole is what the introduction is about.
                        focusStops = if (stopIndex < 0) {
                            null
                        } else {
                            listOfNotNull(pageStop, nextInRoute)
                        },
                        heroHeight = HERO_HEIGHT,
                        // Walking: the map is what you need. Browsing: the photograph is.
                        initialPage = if (isLive) 1 else 0,
                    )

                    // ---- The city introduction, on its own page ------------------------------------
                    // It stays here for the whole tour, not just while it is speaking: stop one's
                    // Previous leads back to it, and the transport lets the walker hear it again.
                    if (stopIndex < 0) {
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
                                    // The neighbouring page, exactly as on a stop page. It used to
                                    // ask the session for state.nextStop, but during the
                                    // introduction there is no current stop for that to be
                                    // relative to, so it answered with whatever stop came next in
                                    // its own bookkeeping and the button jumped deep into the
                                    // tour — stop nine, in the owner's case. On the introduction,
                                    // "next" can only mean the page after it.
                                    onPrevious = { moveToPage(page - 1) },
                                    onNext = { moveToPage(page + 1) },
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

                    // ---- Moving on to stop one ----------------------------------------------------
                    // At the end of the page and in the stop footer's style: a Column with the same
                    // 16 dp inset, a divider, and a full-width button. The introduction runs for
                    // minutes, so there has to be a way past it.
                    if (stopIndex < 0) {
                        Column(Modifier.padding(16.dp)) {
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    // Skipping the introduction hands control to the geofences, then
                                    // shows stop one. Settling on its page starts it, which is what
                                    // stops the walker being left on the introduction.
                                    session.skipIntroduction()
                                    // Stop one is a page of its own now: land at its top, the way
                                    // arriving at any stop does.
                                    pageScrollStates.getOrNull(page + 1)?.let { next -> scope.launch { next.scrollTo(0) } }
                                    moveToPage(page + 1)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Next")
                            }
                        }
                    }

                    // ---- This stop ------------------------------------------------------------------
                    pageStop?.let { current ->
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
                                onPrevious = { moveToPage(previousPage) },
                                onNext = { moveToPage(page + 1) },
                                onSeekFraction = { fraction ->
                                    session.seekNarrationTo((fraction * narration.durationMs).toLong())
                                },
                                onRateChange = { session.setNarrationRate(it) },
                                message = if (highlightApplies) narration.message else null,
                            )
                        }

                        Column(Modifier.padding(16.dp)) {
                            SectionTitle("Transcript")
                            Transcript(
                                text = current.narration,
                                highlightStart = if (highlightApplies) narration.highlightStart else 0,
                                highlightEnd = if (highlightApplies) narration.highlightEnd else 0,
                            )
                        }

                        // Visitor information: the reference material that makes this the stop page.
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

                        current.insiderTip.takeIf { it.isNotBlank() }?.let { tip ->
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

                        // Onward directions exist twice over: the curated text from the content, and the
                        // live distance when we actually know where the walker is.
                        current.nextStopDirections.takeIf { it.isNotBlank() }?.let { directions ->
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

                        Column(Modifier.padding(16.dp)) {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    // On stop one while a tour with an introduction is running
                                    // this is enabled, and leads back to the introduction page.
                                    onClick = { moveToPage(previousPage) },
                                    enabled = previousPage >= 0,
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
                                    onClick = { moveToPage(page + 1) },
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
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Nothing to walk yet, or the pager has not finished landing. The place the page will
            // take is held with an empty panel the height of its hero rather than a line of text:
            // there is nothing to read yet, and nothing moves when the real page arrives. Opaque,
            // because it sits over a pager that is drawing.
            if (!isLive || pageStops.isEmpty() || !landingApplied) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT))
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
    /** The introduction is a page of its own, so it belongs in this list too. */
    introShown: Boolean = false,
    introSelected: Boolean = false,
    onPickIntro: () -> Unit = {},
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
                if (introShown) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 3.dp)
                                .clickable { onPickIntro() },
                            colors = CardDefaults.cardColors(
                                containerColor = if (introSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Introduction", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Before you set off",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

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
