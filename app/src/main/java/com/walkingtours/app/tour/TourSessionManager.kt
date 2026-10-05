package com.walkingtours.app.tour

import android.content.Context
import android.util.Log
import com.walkingtours.app.audio.NarrationEngine
import com.walkingtours.app.audio.NarrationState
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TourSessionState())
    val state: StateFlow<TourSessionState> = _state.asStateFlow()

    private val detector = ArrivalDetector()
    private var locationJob: Job? = null

    /** Stops the walker has physically reached, mirrored from the database. */
    private var visited: Set<String> = emptySet()

    /** When the current tour began. Location fixes older than this are never trusted for arrivals. */
    private var tourStartedAtMs: Long = 0L

    private var overviewJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    /**
     * Begin (or resume) a tour.
     *
     * @param startAtStopId start at this stop instead of the first unvisited one. Used when the
     *   walker taps "Start from here" on a stop, which is the whole point of a walking tour you can
     *   join part-way through: nothing about the tour requires starting at stop one.
     */
    fun startTour(tourId: String, startAtStopId: String? = null, fromTheTop: Boolean = false) {
        // Restarting from the top is a deliberate act and outranks a session already running;
        // otherwise it is a no-op, so pressing Start tour while a walk was live did nothing and the
        // screen opened at whatever stop the session had reached.
        if (!fromTheTop && _state.value.tourId == tourId && _state.value.isRunning) return

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
            visited = repository.visitedStopIds(tourId)

            val explicitStart = startAtStopId?.let { id -> stops.firstOrNull { it.id == id } }

            // Where to resume: the first stop not yet completed, in route order.
            //
            // Neither the GPS nor a "last stop" marker. The GPS version fell back to the first
            // remaining stop when there was no fix yet, so starting a tour from another continent
            // recorded stop one as "where you were"; and both versions sent Resume to whatever stop
            // had been touched most recently rather than to the next thing still to see, which is
            // what "resume" means to a walker.
            val firstUnfinished = stops.firstOrNull { it.id !in visited }
            val resumeAt = explicitStart ?: firstUnfinished ?: stops.first()

            // The introduction plays when the walker asks to begin at the start, and otherwise only
            // on a genuinely first start — not on a resume, and not when they joined at stop 7,
            // where two minutes of overview first would be obnoxious.
            //
            // "Asked to" matters because it used to be inferred from stored progress, so Start tour
            // quietly became Resume on any phone that had been used before.
            val isFreshStart = visited.isEmpty() && explicitStart == null
            val hasOverview = !tour?.overviewText.isNullOrBlank()
            val playIntro = (fromTheTop || isFreshStart) && hasOverview

            detector.reset()
            // Stops already visited should not fire again just because the user is standing there.
            visited.forEach { detector.suppress(it) }

            _state.value = TourSessionState(
                tourId = tourId,
                isRunning = true,
                stops = stops,
                visitedStopIds = visited,
                overviewText = tour?.overviewText.orEmpty(),
                overviewImage = tour?.heroImage,
                showingOverview = playIntro,
                currentStopId = if (playIntro) null else resumeAt.id,
                nextStopId = resumeAt.id,
            )

            // Nothing is recorded as "where you were" while the introduction is playing: the walker
            // has not been anywhere yet, and writing the first stop there is what made a tour started
            // from another continent look as though stop one had been reached.
            repository.startOrResumeTour(tourId, resumeAt.id.takeUnless { playIntro })
            observeLocation()
            locationTracker.acquire()
            // Promote to a foreground service so the tour survives the screen going off.
            TourForegroundService.start(context)

            when {
                playIntro -> {
                    narration.play(OVERVIEW_ID, tour.overviewText)
                    watchForOverviewEnd()
                }
                // Joined at a specific stop: start talking about it straight away.
                //
                // Deliberately not markArrivedHere. Choosing to begin at a stop is not the same as
                // having stood in front of it, and recording an arrival wrote a visit the walker
                // had not made — which, now that tapping a stop is a way in, happened on every tap
                // from anywhere in the world. "I was here" is the checkbox on the stop.
                explicitStart != null -> playStop(explicitStart.id)
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
        visited.forEach { detector.suppress(it) }
        _state.value = current.copy(showingOverview = true, currentStopId = null)
        narration.play(OVERVIEW_ID, current.overviewText)
        watchForOverviewEnd()
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
        locationJob?.cancel()
        locationJob = null
        overviewJob?.cancel()
        overviewJob = null
        locationTracker.release()
        narration.stop()
        detector.reset()
        TourForegroundService.stop(context)
        _state.value = _state.value.copy(
            isRunning = false,
            arrivedStopId = null,
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

        val arrived = detector.update(stops, lat, lng)

        // Guidance always aims at the closest stop still to see, so joining the tour part-way
        // through, or wandering off the route, both behave sensibly.
        val nextStop = nearestUnvisited(stops, lat, lng)
        val nextId = nextStop?.id
        val distance = nextStop?.let { detector.distanceTo(it, lat, lng) }
        val bearing = nextStop?.let { Geo.bearingDegrees(lat, lng, it.lat, it.lng) }

        if (arrived != null) {
            Log.i(TAG, "Arrived at ${arrived.name}")
            visited = visited + arrived.id
            // Update the route target to the following stop so the "walk to" card stays useful.
            // Recomputed against the *new* nearest target. Reusing the distance measured to the
            // stop we just reached would report roughly zero metres to the next one.
            val following = nearestUnvisited(stops, lat, lng, excludeId = arrived.id)
            val followingDistance = following?.let { detector.distanceTo(it, lat, lng) }
            val followingBearing = following?.let { Geo.bearingDegrees(lat, lng, it.lat, it.lng) }
            scope.launch { repository.recordArrival(current.tourId!!, arrived.id) }
            _state.value = current.copy(
                userLat = lat,
                userLng = lng,
                userAccuracyMeters = accuracyMeters,
                userBearingDegrees = courseDegrees,
                visitedStopIds = visited,
                arrivedStopId = arrived.id,
                currentStopId = arrived.id,
                nextStopId = following?.id,
                distanceToNextMeters = followingDistance,
                bearingToNextDegrees = followingBearing,
                // Reaching a stop ends the introduction phase.
                showingOverview = false,
            )
            // The whole point of the app: reaching a stop starts the audio immediately.
            playStop(arrived.id, autoTriggered = true)
            return
        }

        _state.value = current.copy(
            userLat = lat,
            userLng = lng,
            userAccuracyMeters = accuracyMeters,
            userBearingDegrees = courseDegrees,
            visitedStopIds = visited,
            nextStopId = nextId,
            distanceToNextMeters = distance,
            bearingToNextDegrees = bearing,
        )
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
            visited.forEach { detector.suppress(it) }
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
     * Play a stop's narration. Used both by the geofence and by the user tapping a stop in the
     * list, which is what makes the tour usable without physically being there.
     */
    fun playStop(stopId: String, autoTriggered: Boolean = false) {
        val current = _state.value
        val stop = current.stops.firstOrNull { it.id == stopId } ?: return
        // A manual listen must not be interrupted by the geofence firing for the same stop.
        detector.suppress(stopId)
        _state.value = current.copy(
            currentStopId = stopId,
            lastPlaybackWasAuto = autoTriggered,
            showingOverview = false,
        )
        narration.play(stop.id, stop.narration)
        // Keep Resume pointing at the stop the walker is actually on. Done here rather than only on
        // arrival, because stepping through the tour by hand is just as much "where I am".
        scope.launch { repository.rememberLastStop(current.tourId ?: return@launch, stop.id) }
    }

    fun pauseNarration() = narration.pause()

    fun resumeNarration() = narration.resume()

    fun stopNarration() = narration.stop()

    /** Media notification: jump within the current narration. */
    fun seekNarrationTo(positionMs: Long) = narration.seekTo(positionMs)

    /** Media notification: the 15-second rewind and fast-forward buttons. */
    fun skipNarrationBy(deltaMs: Long) = narration.skipBy(deltaMs)

    fun setNarrationRate(rate: Float) = narration.setRate(rate)

    fun acknowledgeArrival() {
        _state.value = _state.value.copy(arrivedStopId = null)
    }

    /** The user said "I am here" manually; treat it exactly like a geofence arrival. */
    fun markArrivedHere(stopId: String) {
        val current = _state.value
        val tourId = current.tourId ?: return
        visited = visited + stopId
        scope.launch { repository.recordArrival(tourId, stopId) }
        detector.suppress(stopId)
        _state.value = current.copy(visitedStopIds = visited, arrivedStopId = stopId)
        playStop(stopId, autoTriggered = true)
    }

    /**
     * Tick or untick a stop by hand, without pretending the walker arrived. Takes the tour id
     * explicitly because a stop can be read before any tour is running.
     */
    fun setStopVisited(tourId: String, stopId: String, isVisited: Boolean) {
        val current = _state.value
        scope.launch { repository.setStopVisited(tourId, stopId, isVisited) }
        if (current.tourId != tourId) return

        visited = if (isVisited) visited + stopId else visited - stopId
        if (isVisited) detector.suppress(stopId)
        val next = if (current.stops.isEmpty()) {
            current.nextStopId
        } else {
            nearestUnvisited(current.stops, current.userLat, current.userLng)?.id
        }
        _state.value = current.copy(visitedStopIds = visited, nextStopId = next)
    }

    fun resetProgress() {
        val tourId = _state.value.tourId ?: return
        scope.launch {
            repository.resetTour(tourId)
            visited = emptySet()
            detector.reset()
            _state.value = _state.value.copy(
                visitedStopIds = emptySet(),
                arrivedStopId = null,
                currentStopId = _state.value.stops.firstOrNull()?.id,
                nextStopId = _state.value.stops.firstOrNull()?.id,
            )
        }
    }

    /** Preview audio for a stop the user is not standing at, without touching tour progress. */
    fun previewStop(stopId: String) {
        val stop = _state.value.stops.firstOrNull { it.id == stopId }
        if (stop != null) {
            detector.suppress(stopId)
            narration.play(stop.id, stop.narration)
            return
        }
        // The session may not be running at all, e.g. opened from the tour detail screen.
        scope.launch {
            repository.getStop(stopId)?.let { narration.play(it.id, it.narration) }
        }
    }

    fun isPlaying(): Boolean {
        val s = narration.progress.value.state
        return s == NarrationState.PLAYING || s == NarrationState.PREPARING
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
