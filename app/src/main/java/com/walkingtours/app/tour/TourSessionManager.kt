package com.walkingtours.app.tour

import android.content.Context
import android.util.Log
import com.walkingtours.app.ai.GuideController
import com.walkingtours.app.ai.NarrationRequest
import com.walkingtours.app.audio.NarrationEngine
import com.walkingtours.app.audio.NarrationState
import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.data.TourRepository
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.location.ArrivalDetector
import com.walkingtours.app.location.LocationTracker
import com.walkingtours.app.service.TourForegroundService
import com.walkingtours.app.util.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything that has to keep working while the walker is moving: location fixes, geofence
 * arrivals, narration playback and progress.
 *
 * It is deliberately a single app-scoped object rather than something owned by a screen. A tour
 * outlives any one screen — the user will pocket the phone, lock it, and keep walking — so the
 * session must not be tied to a composable's lifetime.
 */
class TourSessionManager(
    private val context: Context,
    private val repository: TourRepository,
    private val locationTracker: LocationTracker,
    val narration: NarrationEngine,
    private val aiSettings: AiSettings,
    private val guide: GuideController,
) {

    /** Whether chapters start on their own (arrival/start) or only when the user presses play. */
    private val autoPlay: Boolean get() = aiSettings.current.autoPlayChapters

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TourSessionState())
    val state: StateFlow<TourSessionState> = _state.asStateFlow()

    private val detector = ArrivalDetector()
    private var locationJob: Job? = null

    /** Stops the walker has physically reached, mirrored from the database. */
    private var visited: Set<String> = emptySet()

    /**
     * Stops whose geofence has already fired this run, plus any started by hand. A stop appears here
     * at most once, which is what makes an arrival fire once and never again even if the walker
     * wanders back through its radius.
     */
    private var fired: MutableSet<String> = mutableSetOf()

    /** When the current tour began. Location fixes older than this are never trusted for arrivals. */
    private var tourStartedAtMs: Long = 0L

    private var overviewJob: Job? = null

    /**
     * Loading and starting the narration for the page on screen. Held so a new page cancels the
     * previous load — swiping on before a stop's text is ready skips it rather than playing it late.
     */
    private var narrationJob: Job? = null

    /**
     * Bumped by every start and every teardown, so an in-flight start that is superseded — the user
     * pressed Back while the tour was still loading — cannot install itself afterwards and leave a
     * walk running with nothing on screen.
     */
    private var sessionToken = 0

    // ---------------------------------------------------------------- lifecycle

    /**
     * Begin — or move around — a tour.
     *
     * Every way in arrives here as one [TourEntry], which resolves to a single landing page: the
     * introduction, or one stop. There is no separate resume flag, no "start from here" path and no
     * guessing from stored progress, because a tour is always one introduction plus N stops and the
     * entry is the only thing that says which of them to land on.
     */
    fun startTour(tourId: String, entry: TourEntry) {
        val current = _state.value
        if (current.tourId == tourId && current.isRunning) {
            // Already walking this tour, so the entry has done its job: from here an arrival or a
            // swipe decides the page. Re-applying it must not drag the walker back — which is what a
            // rotation would otherwise do — so Start over is the only entry that still means
            // something.
            if (entry == TourEntry.StartOver) {
                teardown()
                begin(tourId, entry)
            }
            return
        }
        begin(tourId, entry)
    }

    private fun begin(tourId: String, entry: TourEntry) {
        // Switching tours: the old walk's location lease and geofences go first, or the new tour
        // would hold two leases and never release either.
        if (_state.value.isRunning) teardown()
        val token = ++sessionToken

        narration.prepare { ok ->
            Log.i(TAG, "Narration engine ready=$ok (${narration.engineLabel})")
        }

        scope.launch {
            repository.ensureContentLoaded()
            tourStartedAtMs = System.currentTimeMillis()
            val tour = repository.getTour(tourId)
            val stops = repository.getStops(tourId)
            if (stops.isEmpty()) {
                Log.w(TAG, "Tour $tourId has no stops; not starting.")
                return@launch
            }
            // Wiping progress has to happen before the visits are read, or the walker starts over
            // with every stop still suppressed and no geofence can fire.
            if (entry == TourEntry.StartOver) repository.resetTour(tourId)
            visited = repository.visitedStopIds(tourId)
            // Everything below is synchronous: nothing can supersede this start now that the tour is
            // loaded and the walker is still waiting for it.
            if (token != sessionToken) return@launch

            val overview = tour?.overviewText.orEmpty()
            // The introduction plays when — and only when — the walker asked to begin at the start.
            // It used to be inferred from stored progress, so Start tour quietly became Resume on a
            // phone used before, and a resume that had completed nothing replayed the introduction
            // instead of going to the first stop still to see.
            val playIntro = overview.isNotBlank() && entry.beginsAtIntroduction

            // Where to land, in one place. Resume means the first stop still to see, in route order
            // — never the nearest one and never a "last stop" marker, either of which sends a walker
            // somewhere other than the next thing to look at.
            val firstUnfinished = stops.firstOrNull { it.id !in visited } ?: stops.first()
            val landing = when {
                playIntro -> null
                entry is TourEntry.Stop -> stops.firstOrNull { it.id == entry.stopId } ?: firstUnfinished
                else -> firstUnfinished
            }

            detector.reset()
            // Stops already reached on an earlier run must never fire again; the order rule below
            // also keeps the geofences on the route the walker is actually walking.
            fired = visited.toMutableSet()

            _state.value = TourSessionState(
                tourId = tourId,
                isRunning = true,
                stops = stops,
                visitedStopIds = visited,
                overviewText = overview,
                overviewImage = tour?.heroImage,
                showingOverview = playIntro,
                currentStopId = landing?.id,
                nextStopId = (landing ?: firstUnfinished).id,
            )

            // Nothing is recorded as "where you were" while the introduction is playing: the walker
            // has not been anywhere yet, and writing the first stop there is what made a tour started
            // from another continent look as though stop one had been reached.
            repository.startOrResumeTour(tourId, landing?.id.takeUnless { playIntro })
            observeLocation()
            locationTracker.acquire()
            // Promote to a foreground service so the tour survives the screen going off.
            TourForegroundService.start(context)

            when {
                playIntro && autoPlay -> {
                    playOverviewNarration(tourId, overview)
                    watchForOverviewEnd()
                }
                // In manual mode the introduction is still the landing page the walker reads, but
                // nothing plays until they press play (which arms watchForOverviewEnd) or Next.
                playIntro -> Unit
                // Resume lands on a stop the walker is walking towards rather than standing at, so
                // its narration waits for the geofence to start it on arrival. Every other entry is
                // a request to hear that stop now.
                entry == TourEntry.Resume -> Unit
                // A Stop entry means the walker asked to open that page; in manual mode the page
                // is all that opens — the narration still waits for a press of play.
                landing != null -> if (autoPlay) playStop(landing.id) else focusStop(landing.id)
            }
        }
    }

    /**
     * The stop to walk to next: the nearest one not yet reached.
     *
     * Deliberately not "the next one in route order". A walker can join the tour anywhere, and if
     * they have already reached stop two, pointing them back across the square to stop one is
     * useless. Order is only the fallback when there is no location fix yet.
     */
    private fun nearestUnvisited(
        stops: List<StopEntity>,
        lat: Double?,
        lng: Double?,
        excludeId: String? = null,
    ): StopEntity? {
        val remaining = stops.filter { it.id !in visited && it.id != excludeId }
        if (remaining.isEmpty()) return null
        if (lat == null || lng == null) return remaining.first()
        return remaining.minByOrNull { Geo.distanceMeters(lat, lng, it.lat, it.lng) }
    }

    /** Media notification: jump to the following stop in route order, wrapping at the end. */
    fun playNextStop() = stepStop(+1)

    /** Media notification: jump to the previous stop in route order, wrapping at the start. */
    fun playPreviousStop() = stepStop(-1)

    private fun stepStop(delta: Int) {
        val current = _state.value
        val stops = current.stops
        if (stops.isEmpty()) return
        val index = stops.indexOfFirst { it.id == current.currentStopId }
        val target = when {
            index < 0 -> stops.first()
            else -> stops[(index + delta + stops.size) % stops.size]
        }
        playStop(target.id)
    }

    /** Geofencing takes over as soon as the introduction has finished being read. */
    private fun watchForOverviewEnd() {
        overviewJob?.cancel()
        overviewJob = scope.launch {
            narration.progress.collect { progress ->
                if (progress.stopId == OVERVIEW_ID &&
                    progress.state == NarrationState.COMPLETED
                ) {
                    Log.i(TAG, "Introduction finished; geofences are now live")
                    endOverview()
                    overviewJob?.cancel()
                }
            }
        }
    }

    /** Replay the spoken introduction to the city, e.g. if the walker missed it. */
    fun playOverview() {
        val current = _state.value
        if (current.overviewText.isBlank()) return
        detector.reset()
        _state.value = current.copy(showingOverview = true, currentStopId = null)
        current.tourId?.let { playOverviewNarration(it, current.overviewText) }
        watchForOverviewEnd()
    }

    /**
     * Load the guide's version of the introduction — if there is one — then speak it. While the
     * model is working, the page shows its loading state; nothing plays until the text is ready.
     */
    private fun playOverviewNarration(tourId: String, overview: String) {
        // A new chapter cuts the previous audio at once: the replacement only arrives after its
        // text is generated, and the old stop must not keep talking through the loading state.
        narration.stop()
        guide.ensureVoiceMatchesGuide(tourId)
        narrationJob?.cancel()
        narrationJob = scope.launch {
            val line = guide.narration(
                tourId,
                NarrationRequest(OVERVIEW_ID, "the introduction to the walk", overview, isIntroduction = true),
            )
            narration.play(OVERVIEW_ID, line.text, style = line.style)
        }
    }

    /**
     * Load the guide's version of the introduction for the page to read, without playing it.
     *
     * The introduction is not a stop, so nothing plays automatically when its page settles. Without
     * this, swiping back to the introduction showed the authored text until the walker pressed play.
     */
    fun prepareOverviewNarration() {
        val current = _state.value
        val tourId = current.tourId ?: return
        val overview = current.overviewText
        if (overview.isBlank()) return
        scope.launch {
            guide.narration(
                tourId,
                NarrationRequest(OVERVIEW_ID, "the introduction to the walk", overview, isIntroduction = true),
            )
        }
    }

    fun endTour() = teardown()

    /**
     * Stop everything because the user closed the app — pressing back out of it, or swiping it out
     * of Recents.
     *
     * This matters because narration is spoken by the system text-to-speech engine, which lives in
     * its own process. Killing our UI would not silence it: the engine happily finishes the current
     * utterance on its own. Worse, the foreground service keeps our process alive after a Recents
     * swipe, so without this the phone would carry on narrating a walking tour to an empty street.
     * Stopping the engine explicitly is the only reliable way to cut the audio.
     */
    fun shutdown() {
        Log.i(TAG, "App closed: stopping narration and location updates")
        teardown()
    }

    private fun teardown() {
        // Any start still in flight belongs to a tour the walker has now left.
        sessionToken++
        locationJob?.cancel()
        locationJob = null
        overviewJob?.cancel()
        overviewJob = null
        narrationJob?.cancel()
        narrationJob = null
        locationTracker.release()
        narration.stop()
        detector.reset()
        TourForegroundService.stop(context)
        _state.value = _state.value.copy(
            isRunning = false,
            showingOverview = false,
        )
    }

    private fun observeLocation() {
        locationJob?.cancel()
        locationJob = scope.launch {
            locationTracker.lastLocation.collect { location ->
                if (location == null) return@collect
                onPosition(
                    lat = location.latitude,
                    lng = location.longitude,
                    fixTimeMs = location.time,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    courseDegrees = if (location.hasBearing()) location.bearing else null,
                )
            }
        }
        scope.launch {
            locationTracker.providerIssue.collect { issue ->
                _state.value = _state.value.copy(locationIssue = issue)
            }
        }
    }

    /**
     * @param fixTimeMs when the platform produced the fix. Fixes older than the tour itself are
     *   used to draw the map but never to trigger a stop: the "last known location" handed over at
     *   startup can be hours old or from another city entirely, and acting on it would fire an
     *   arrival the moment the user pressed Start.
     */
    private fun onPosition(
        lat: Double,
        lng: Double,
        fixTimeMs: Long,
        accuracyMeters: Float? = null,
        courseDegrees: Float? = null,
    ) {
        val current = _state.value
        val stops = current.stops
        if (stops.isEmpty()) return

        // While the introduction is playing, follow the walker's position but do not let a geofence
        // cut the city overview short. Geofencing goes live the moment it finishes.
        if (current.showingOverview) {
            updatePositionOnly(current, lat, lng, accuracyMeters, courseDegrees)
            return
        }

        if (fixTimeMs < tourStartedAtMs) {
            updatePositionOnly(current, lat, lng, accuracyMeters, courseDegrees)
            return
        }

        // Which stops currently contain the walker, by their arrival radii and with hysteresis.
        val inside = detector.update(stops, lat, lng)

        // With two geofences overlapping, the one that fires is the one the walker is nearest to; the
        // other waits until they are closer to it. So the nearest stop currently inside is the only
        // candidate, and it is dropped unless the route order allows it and it has not fired before.
        // Once the walker moves past the first, the second becomes the nearest and fires.
        val nearestInside = inside.minByOrNull { detector.distanceTo(it, lat, lng) }
        val candidate = nearestInside?.takeIf { stop ->
            stop.id !in fired && stop.id !in visited && isNextInOrder(stops, stop)
        }

        // Narration that is already playing — or still being written for a stop just reached — is
        // never cut off by a geofence. A stop the walker is standing in while the previous one talks
        // simply waits: it stays inside on every fix, so it fires on the first fix after the audio
        // ends, without them having to leave and come back.
        val audioBusy = narration.progress.value.state == NarrationState.PLAYING ||
            narration.progress.value.state == NarrationState.PREPARING ||
            narrationJob?.isActive == true

        if (candidate != null && !audioBusy) {
            Log.i(TAG, "Arrived at ${candidate.name}")
            fired += candidate.id
            visited = visited + candidate.id
            // Update the route target to the following stop so the guidance stays useful.
            // Recomputed against the *new* nearest target. Reusing the distance measured to the
            // stop we just reached would report roughly zero metres to the next one.
            val following = nearestUnvisited(stops, lat, lng, excludeId = candidate.id)
            val followingDistance = following?.let { detector.distanceTo(it, lat, lng) }
            val followingBearing = following?.let { Geo.bearingDegrees(lat, lng, it.lat, it.lng) }
            scope.launch { repository.recordArrival(current.tourId!!, candidate.id) }
            _state.value = current.copy(
                userLat = lat,
                userLng = lng,
                userAccuracyMeters = accuracyMeters,
                userBearingDegrees = courseDegrees,
                visitedStopIds = visited,
                currentStopId = candidate.id,
                nextStopId = following?.id,
                distanceToNextMeters = followingDistance,
                bearingToNextDegrees = followingBearing,
                // Reaching a stop ends the introduction phase.
                showingOverview = false,
            )
            // The whole point of the app: reaching a stop starts the audio immediately — unless the
            // walker chose manual chapters, in which case the page still moves and the stop is
            // recorded, but the narration waits for a press of play.
            if (autoPlay) {
                playStop(candidate.id)
            } else {
                // Keep Resume pointing at the stop the walker is actually on, same as playStop does.
                scope.launch { repository.rememberLastStop(current.tourId!!, candidate.id) }
            }
            return
        }

        // No arrival to act on: refresh the dot and the guidance only.
        val nextStop = nearestUnvisited(stops, lat, lng)
        _state.value = current.copy(
            userLat = lat,
            userLng = lng,
            userAccuracyMeters = accuracyMeters,
            userBearingDegrees = courseDegrees,
            visitedStopIds = visited,
            nextStopId = nextStop?.id,
            distanceToNextMeters = nextStop?.let { detector.distanceTo(it, lat, lng) },
            bearingToNextDegrees = nextStop?.let { Geo.bearingDegrees(lat, lng, it.lat, it.lng) },
        )
    }

    /**
     * True when [stop] is allowed onto the geofence: the first stop of the route, or one whose
     * immediate predecessor on the route has already been reached or started. This is what stops a
     * walker standing by stop nine from having it fire while stop two is still the one to see.
     */
    private fun isNextInOrder(stops: List<StopEntity>, stop: StopEntity): Boolean {
        val index = stops.indexOfFirst { it.id == stop.id }
        if (index < 0) return false
        if (index == 0) return true
        val previous = stops[index - 1]
        return previous.id in visited || previous.id in fired
    }

    /** Refresh the map dot and the distance to the next stop without any arrival side effects. */
    private fun updatePositionOnly(
        current: TourSessionState,
        lat: Double,
        lng: Double,
        accuracyMeters: Float?,
        courseDegrees: Float?,
    ) {
        val nextStop = nearestUnvisited(current.stops, lat, lng) ?: return
        val nextId = nextStop.id
        _state.value = current.copy(
            userLat = lat,
            userLng = lng,
            userAccuracyMeters = accuracyMeters,
            userBearingDegrees = courseDegrees,
            nextStopId = nextId,
            distanceToNextMeters = detector.distanceTo(nextStop, lat, lng),
            bearingToNextDegrees = Geo.bearingDegrees(lat, lng, nextStop.lat, nextStop.lng),
        )
    }

    /**
     * Ends the city introduction early, for the Next button on the introduction.
     *
     * Same path as the introduction finishing on its own, so skipping it hands control to the
     * geofences and evaluates the walker's position exactly as waiting would have.
     */
    fun skipIntroduction() = endOverview()

    /**
     * Once the introduction finishes, hand control to the geofences and immediately evaluate the
     * walker's current position, so someone who pressed Start while already standing at stop one
     * still gets its narration without having to walk out of range and back.
     */
    private fun endOverview() {
        val current = _state.value
        if (!current.showingOverview) return
        _state.value = current.copy(showingOverview = false)
        val lat = current.userLat
        val lng = current.userLng
        if (lat != null && lng != null) {
            detector.reset()
            onPosition(
                lat = lat,
                lng = lng,
                fixTimeMs = System.currentTimeMillis(),
                accuracyMeters = current.userAccuracyMeters,
                courseDegrees = current.userBearingDegrees,
            )
        }
    }

    // ---------------------------------------------------------------- narration

    /**
     * Play a stop's narration and make it the current page. Used by the geofence on arrival and by
     * any of the ways in that land the walker on a stop.
     */
    fun playStop(stopId: String) {
        val current = _state.value
        val stop = current.stops.firstOrNull { it.id == stopId } ?: return
        // A manual listen must not be interrupted by the geofence firing for the same stop, and a
        // stop heard by hand counts as handled for the route-order rule.
        fired += stopId
        _state.value = current.copy(
            currentStopId = stopId,
            showingOverview = false,
        )
        // Keep Resume pointing at the stop the walker is actually on. Done here rather than only on
        // arrival, because stepping through the tour by hand is just as much "where I am".
        scope.launch { repository.rememberLastStop(current.tourId ?: return@launch, stop.id) }

        // Load the guide's version of this stop — a cached one is instant — then speak it. The page
        // shows its loading state in the meantime, so the words on screen and the voice always match.
        // The previous stop's audio stops now, not when the new text arrives: swiping on must never
        // leave two stops talking over each other.
        val tourId = current.tourId ?: return
        narration.stop()
        guide.ensureVoiceMatchesGuide(tourId)
        narrationJob?.cancel()
        narrationJob = scope.launch {
            val line = guide.narration(
                tourId,
                NarrationRequest(stop.id, "Stop ${stop.order}: ${stop.name} (${stop.category})", stop.narration),
            )
            narration.play(stop.id, line.text, style = line.style)
        }
    }

    /**
     * Manual playback: make a stop current and update the tour bookkeeping, but do not start its
     * narration. The walker pressed nothing toward audio — they only moved to the page — so the
     * chapter waits for an explicit press of play.
     */
    fun focusStop(stopId: String) {
        val current = _state.value
        val stop = current.stops.firstOrNull { it.id == stopId } ?: return
        fired += stopId
        _state.value = current.copy(currentStopId = stopId, showingOverview = false)
        scope.launch { repository.rememberLastStop(current.tourId ?: return@launch, stopId) }

        // Load the guide's version for the page to read, without starting it: in manual mode nothing
        // plays until the walker presses play.
        val tourId = current.tourId ?: return
        scope.launch {
            guide.narration(
                tourId,
                NarrationRequest(stop.id, "Stop ${stop.order}: ${stop.name} (${stop.category})", stop.narration),
            )
        }
    }

    /**
     * Load a stop's narration for reading, without playing it. Used when the guide or the walker's
     * feedback changes and the page needs the new text without an explicit play.
     */
    fun prepareStopNarration(stopId: String) {
        val current = _state.value
        val stop = current.stops.firstOrNull { it.id == stopId } ?: return
        val tourId = current.tourId ?: return
        scope.launch {
            guide.narration(
                tourId,
                NarrationRequest(stop.id, "Stop ${stop.order}: ${stop.name} (${stop.category})", stop.narration),
            )
        }
    }

    /**
     * Re-roll a stop's narration after the walker asked for a change: drop the cached text and speak
     * the new version. A manual action, so it may interrupt what is playing.
     */
    fun rerollStop(stopId: String) {
        val current = _state.value
        current.tourId?.let { guide.invalidate(it, stopId) }
        playStop(stopId)
    }

    /** Re-roll and replay the introduction after the walker tuned it, as [rerollStop] does a stop. */
    fun rerollOverview() {
        val tourId = _state.value.tourId ?: return
        guide.invalidate(tourId, OVERVIEW_ID)
        playOverview()
    }

    fun pauseNarration() = narration.pause()

    fun resumeNarration() = narration.resume()

    fun stopNarration() = narration.stop()

    /** Media notification: jump within the current narration. */
    fun seekNarrationTo(positionMs: Long) = narration.seekTo(positionMs)

    /** Media notification: the 15-second rewind and fast-forward buttons. */
    fun skipNarrationBy(deltaMs: Long) = narration.skipBy(deltaMs)

    fun setNarrationRate(rate: Float) = narration.setRate(rate)

    /**
     * Tick or untick a stop by hand, without pretending the walker arrived. Takes the tour id
     * explicitly because a stop can be read before any tour is running.
     */
    fun setStopVisited(tourId: String, stopId: String, isVisited: Boolean) {
        val current = _state.value
        scope.launch { repository.setStopVisited(tourId, stopId, isVisited) }
        if (current.tourId != tourId) return

        visited = if (isVisited) visited + stopId else visited - stopId
        if (isVisited) fired += stopId
        val next = if (current.stops.isEmpty()) {
            current.nextStopId
        } else {
            nearestUnvisited(current.stops, current.userLat, current.userLng)?.id
        }
        _state.value = current.copy(visitedStopIds = visited, nextStopId = next)
    }

    companion object {
        private const val TAG = "TourSession"

        /**
         * Pseudo stop id for the tour introduction. The narration engine is keyed by stop id, and
         * giving the overview its own id lets the UI apply the same highlighting logic to it.
         */
        const val OVERVIEW_ID = "__tour_overview__"
    }
}
