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
import androidx.compose.foundation.lazy.LazyListState
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
import com.walkingtours.app.ui.nav.Routes
import com.walkingtours.app.data.db.TourEntity
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

    // Opened from the route list to be read. Not a walk: the tour must not be started, because
    // starting one with no stop named nominates whichever stop the walker is nearest.
    val wantsIntroduction = startAtStopId == Routes.INTRO

    // "Resume" means: make sure the tour is running, then follow the session's current stop.
    LaunchedEffect(tourId, startAtStopId, isResume) {
        if (!isResume) return@LaunchedEffect
        if (wantsIntroduction) {
            // Start tour, and the introduction row, both mean begin at the introduction. Said
            // explicitly rather than inferred from progress, and it restarts rather than no-opping,
            // so it works on a phone that has walked this tour before.
            session.startTour(tourId, fromTheTop = true)
        } else if (state.tourId != tourId || !state.isRunning) {
            session.startTour(tourId, startAtStopId)
        }
    }

    val allStops by produceState(initialValue = emptyList<StopEntity>(), tourId) {
        repository.ensureContentLoaded()
        value = repository.getStops(tourId)
    }

    // The tour's own photograph, for the hero while no stop is current.
    // The tour itself, not just its photograph: the introduction page needs the overview text
    // whether or not a walk is running, so it can be read before committing to the walk.
    val tour by produceState(initialValue = null as TourEntity?, tourId) {
        repository.ensureContentLoaded()
        value = repository.getTour(tourId)
    }
    val tourHeroImage = tour?.heroImage
    val tourOverviewText = tour?.overviewText.orEmpty()
    val stopProgress by remember(tourId) { repository.observeStopProgress(tourId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val visitedIds = stopProgress.map { it.stopId }.toSet()

    // Prefer the tracker's own fix: the session only carries one while a tour is running.
    val mapLat = trackerFix?.latitude ?: state.userLat
    val mapLng = trackerFix?.longitude ?: state.userLng
    val mapAccuracy = trackerFix?.accuracy ?: state.userAccuracyMeters

    val isLive = state.isRunning && state.tourId == tourId

    // The city introduction is a page in its own right, in front of the stops, so it gets an index
    // of its own and every page-to-stop mapping on this screen goes through stopIndexFor: the pager
    // below, the initial landing, following the session's stop, the narration, the app bar.
    //
    // "Has an introduction to show" is deliberately not `state.showingOverview`. That flag is about
    // what is playing this second, and it turns false in three places while the walker is still
    // standing on the page — pressing Next, the narration reaching its end, a geofence arrival — so
    // a page count keyed off it would drop a page out from under the pager and renumber every stop
    // behind the walker's back (stop one's page 1 would quietly become stop two). The tour and its
    // overview text, though, are fixed for as long as that tour is the one running: a live tour with
    // an introduction keeps it as its first page from start to finish, which is exactly what lets
    // stop one's Previous lead back to it long after it has stopped talking. No running tour, or no
    // overview text, means there is nothing to give a page to.
    // Keyed on the tour's own introduction text, not on whether a walk is running: the page exists
    // so the introduction can be read before starting, and so the all-stops list can offer it.
    val introPages = if (tourOverviewText.isNotBlank()) 1 else 0
    fun stopIndexFor(page: Int) = page - introPages

    // A pager rather than a sideways gesture that only navigates on release, because the neighbouring
    // stop has to be visible *while the finger is still down* — the next page follows the drag and
    // then settles, which is what makes moving between stops feel like one continuous surface.
    // Navigation Compose has no gesture-driven transition API, so a pager is the only way to get
    // that, and the pages are the stops themselves with the introduction in front of them.
    val pagerState = rememberPagerState(pageCount = { allStops.size + introPages })
    val scope = rememberCoroutineScope()

    // One scroll position per page, held out here rather than inside each page so the introduction's
    // Next can put stop one back at the top on its way in. A request is not a scroll: the page it
    // names picks the position up the next time it is measured, whether or not it has been composed.
    val pageListStates = remember(allStops.size, introPages) {
        List(allStops.size + introPages) { LazyListState() }
    }

    // The top bar and the AskBar follow the settled page, not whichever stop the screen was opened
    // with, so the title always names the page the walker has landed on.
    //
    // Until the tour names a stop — while the city introduction is playing — there is no stop to
    // name either, and the introduction page, whose stop index is negative, has none to offer
    // whatever the session says. The stops' own pages are no longer held empty to match; they used
    // to be, back when the introduction was a card inside page one and a pager had no "no stop" page
    // for it to live on.
    val noStopYet = isResume && state.currentStopId == null
    val settledStopIndex = stopIndexFor(pagerState.currentPage)
    val settledStop = allStops.getOrNull(settledStopIndex).takeUnless { noStopYet }

    // Land on the stop we were asked for as soon as the list has arrived: the stop that was opened,
    // else wherever the tour has got to, else the first page. It happens once — after that the
    // walker's own swiping is in charge — and while resuming it waits for the session to name a
    // stop rather than yanking the pager to the first page and staying there.
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(allStops, stopId, state.currentStopId, introPages) {
        if (didInitialScroll || allStops.isEmpty() || pagerState.isScrollInProgress) {
            return@LaunchedEffect
        }
        if (wantsIntroduction || state.showingOverview) {
            // Wait for the introduction to have a page at all. The tour's text arrives after the
            // stop list, so page zero is still stop one for a moment, and landing then put the
            // walker on stop one and marked the landing done.
            if (introPages == 0) return@LaunchedEffect
            // The introduction is what belongs on screen: either it was opened deliberately, or it
            // is playing because a walk has just begun. Following the stop named by the route would
            // slide straight past it — pressing Start tour starts at stop one, whose page is now one
            // past the introduction.
            didInitialScroll = true
            pagerState.scrollToPage(0)
            return@LaunchedEffect
        }
        val wanted = when {
            stopId != null -> stopId
            // Resume goes to the first stop still to see, in route order — not to whichever stop the
            // session last had, which on a phone used before was simply the last one touched.
            isResume && allStops.isNotEmpty() ->
                allStops.firstOrNull { it.id !in visitedIds }?.id
            else -> state.currentStopId
        } ?: return@LaunchedEffect
        val stopIndex = allStops.indexOfFirst { it.id == wanted }
        if (stopIndex < 0) return@LaunchedEffect
        didInitialScroll = true
        val target = stopIndex + introPages
        if (target != pagerState.currentPage) pagerState.scrollToPage(target)
    }

    // Inserting or removing the introduction page renumbers every stop: the page that was showing
    // stop five is now showing stop four. That only happens when a tour starts or ends on this
    // screen, so move the walker's page by the same amount and the stop on screen stays the stop on
    // screen. The exception is a tour that begins by playing its introduction — there the
    // introduction is precisely what belongs on screen, and it is page zero.
    var introPagesSeen by remember { mutableStateOf(introPages) }
    LaunchedEffect(introPages) {
        val delta = introPages - introPagesSeen
        introPagesSeen = introPages
        if (delta == 0 || pagerState.pageCount == 0) return@LaunchedEffect
        // Inserting the page renumbers the stops, so normally the walker is carried with the stop
        // they were looking at. Reading the introduction is the exception: page zero is what they
        // asked for, so go there rather than preserving a stop they never chose.
        val target = if (state.showingOverview || wantsIntroduction) 0 else pagerState.currentPage + delta
        pagerState.scrollToPage(target.coerceIn(0, pagerState.pageCount - 1))
    }

    // For as long as the introduction is playing, page zero is what belongs on screen.
    //
    // Its own effect rather than a clause in the ones above, because the ordering is not guaranteed:
    // the introduction page is inserted as soon as the tour's text loads, which can be a moment
    // before the session starts narrating. Renumbering then carried the walker onto stop one, and
    // once the landing was marked done nothing moved them back.
    LaunchedEffect(state.showingOverview, introPages) {
        if (state.showingOverview && introPages > 0) {
            didInitialScroll = true
            if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
        }
    }

    // On the resume route the screen belongs to the session: when the walker reaches the next stop
    // the pager has to follow it, which is what resolving the session's stop used to do. A stop that
    // was opened deliberately is the walker's to leave, so only the resume route follows.
    //
    // introPages is a key as well as a dependency: the offset it supplies has to be the one in force
    // when a stop arrives, and the introduction page comes and goes underneath this effect.
    LaunchedEffect(isResume, allStops, introPages) {
        if (!isResume) return@LaunchedEffect
        // Reading the introduction is the walker's own choice of page, and it outranks the session.
        if (wantsIntroduction) return@LaunchedEffect
        // An arrival, not the current stop.
        //
        // Following currentStopId mirrored the session back into the pager while the pager was
        // already telling the session which stop was showing (settled page -> playStop ->
        // currentStopId -> scroll). Two effects steering the same thing in opposite directions
        // oscillate the moment they disagree, which they did on resume: the recorded stop against
        // the nearest one. arrivedStopId is one-shot, so it moves the page once and stops.
        snapshotFlow { state.arrivedStopId }.collect { id ->
            if (id == null || !didInitialScroll) return@collect
            val stopIndex = allStops.indexOfFirst { it.id == id }
            if (stopIndex >= 0 && stopIndex + introPages != pagerState.currentPage) {
                pagerState.animateScrollToPage(stopIndex + introPages)
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
                // A negative stop index is the introduction, which has no narration of its own to
                // start; the null check that already lived here covers it.
                val id = allStops.getOrNull(stopIndexFor(page))?.id ?: return@collect
                if (isLive && session.state.value.currentStopId != id) session.playStop(id)
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
            stops = allStops,
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
                // Jumping around the list slides the pager across rather than pushing a new screen;
                // the settled page is what makes the stop current while a tour is running.
                val pickedIndex = allStops.indexOfFirst { it.id == picked }
                if (pickedIndex >= 0) moveToPage(pickedIndex + introPages)
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
                                // The introduction is a page of its own and has no stop number to
                                // quote: "Stop 1 of 14" here would name a stop the walker is not
                                // looking at.
                                settledStopIndex < 0 -> "Introduction"

                                isLive && state.stops.isNotEmpty() ->
                                    "Walking \u00b7 ${state.visitedStopIds.size} of ${state.stops.size} reached"

                                allStops.isNotEmpty() && !noStopYet ->
                                    "Stop ${settledStopIndex + 1} of ${allStops.size}"

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
                // Everything this page shows comes from the stop it maps to: the stop itself, whether
                // that stop is the tour's current one, whether the narration highlighting belongs to
                // it, and the neighbours it can move to. The introduction page — page zero when the
                // tour has one — maps to no stop at all and shows the introduction instead. Stop
                // pages are no longer blanked while it plays, so swiping on to stop one lands on the
                // stop itself.
                val stopIndex = stopIndexFor(page)
                val pageStop = allStops.getOrNull(stopIndex)
                val pageStopId = pageStop?.id
                val isCurrentStop = pageStopId != null && pageStopId == state.currentStopId
                val highlightApplies = isCurrentStop && narration.stopId == pageStopId
                val previousPage = page - 1
                val nextInRoute = allStops.getOrNull(stopIndex + 1)

                // The page keeps its scroll position for as long as the screen is open; the
                // introduction's Next reaches into the page it opens to start it at the top.
                val listState = pageListStates[page]

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
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

                    item {
                        StopHero(
                            stop = pageStop,
                            stops = if (isLive) state.stops else allStops,
                            fallbackPhotoAsset = tourHeroImage,
                            visitedIds = visitedIds,
                            userLat = mapLat,
                            userLng = mapLng,
                            userHeading = compassHeading ?: state.userBearingDegrees,
                            userAccuracyMeters = mapAccuracy,
                            onOpenStop = { onOpenStop(it.id) },
                            // Frame this stop and the next one rather than the entire route: the useful
                            // question on a stop page is "where do I go next", not "where does this walk
                            // go in total".
                            // The whole route while the introduction is what is showing: there is no
                            // "next stop" yet, and the tour as a whole is what the introduction is about.
                            focusStops = if (noStopYet || stopIndex < 0) {
                                null
                            } else {
                                listOfNotNull(pageStop, nextInRoute)
                            },
                            heroHeight = 240.dp,
                            // Walking: the map is what you need. Browsing: the photograph is.
                            initialPage = if (isLive) 1 else 0,
                        )
                    }

                    // ---- The city introduction, on its own page ------------------------------------
                    // It stays here for the whole tour, not just while it is speaking: stop one's
                    // Previous leads back to it, and the transport lets the walker hear it again.
                    if (stopIndex < 0) {
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
                                        text = state.overviewText.ifBlank { tourOverviewText },
                                        highlightStart = if (overviewPlaying) narration.highlightStart else 0,
                                        highlightEnd = if (overviewPlaying) narration.highlightEnd else 0,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }

                    // ---- Moving on to stop one ----------------------------------------------------
                    // At the end of the page and in the stop footer's style: a Column with the same
                    // 16 dp inset, a divider, and a full-width button. The introduction runs for
                    // minutes, so there has to be a way past it.
                    if (stopIndex < 0) {
                        item {
                            Column(Modifier.padding(16.dp)) {
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        // Past the introduction, then straight to the first stop:
                                        // someone skipping it wants to be walking, and leaving them on
                                        // "no current stop" would present a different dead end.
                                        val firstStop = allStops.firstOrNull()
                                        if (firstStop != null) {
                                            if (isLive) {
                                                session.skipIntroduction()
                                                session.playStop(firstStop.id)
                                            } else {
                                                // Browsing, so this is the walker setting off.
                                                // playStop with nothing running answers from wherever
                                                // they happen to be standing, which put them on whatever
                                                // stop was nearest instead of the first one.
                                                session.startTour(tourId, firstStop.id)
                                            }
                                        }
                                        // The walker is moving on by hand, so the landing a resume does
                                        // must not snap them back to wherever the session has got to.
                                        didInitialScroll = true
                                        // Stop one is a page of its own now: land at its top, the way
                                        // arriving at any stop does.
                                        pageListStates.getOrNull(page + 1)?.requestScrollToItem(0)
                                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Next")
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
                                    onPrevious = { moveToPage(previousPage) },
                                    onNext = { moveToPage(page + 1) },
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
