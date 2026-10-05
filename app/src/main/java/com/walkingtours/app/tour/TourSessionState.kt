package com.walkingtours.app.tour

import com.walkingtours.app.data.db.StopEntity

/**
 * Snapshot of an in-progress tour, observed by the active-tour and stop screens.
 *
 * The tour is one introduction plus N stops, and [currentStopId] names the page the walker is on:
 * null means the introduction. Everything else about navigation is derived from that one value.
 */
data class TourSessionState(
    val tourId: String? = null,
    val isRunning: Boolean = false,
    val stops: List<StopEntity> = emptyList(),
    val visitedStopIds: Set<String> = emptySet(),
    /** The stop the tour is on, or null while the introduction is the page. */
    val currentStopId: String? = null,
    /** Stop the walker should be heading towards next. */
    val nextStopId: String? = null,
    val userLat: Double? = null,
    val userLng: Double? = null,
    /** GPS accuracy in metres, drawn as the accuracy circle on the map. */
    val userAccuracyMeters: Float? = null,
    /** GPS course over ground. Null unless the walker is actually moving. */
    val userBearingDegrees: Float? = null,
    val distanceToNextMeters: Double? = null,
    val bearingToNextDegrees: Double? = null,
    val locationIssue: String? = null,
    /** Spoken introduction to the city and the walk, played when a tour begins. */
    val overviewText: String = "",
    val overviewImage: String? = null,
    /**
     * True while the introduction is the current page and has not been finished or skipped.
     *
     * Location and geofences are live from the moment the tour starts, but arrivals are held back
     * while this is true so that walking into stop one does not cut the introduction off mid-
     * sentence. Skipping it or reaching the end of the narration clears it and evaluates the
     * walker's position immediately.
     */
    val showingOverview: Boolean = false,
) {
    val currentStop: StopEntity? get() = stops.firstOrNull { it.id == currentStopId }
}
