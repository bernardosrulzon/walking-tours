package com.walkingtours.app.tour

import com.walkingtours.app.data.db.StopEntity

/**
 * Snapshot of an in-progress tour, observed by the active-tour and stop screens.
 */
data class TourSessionState(
    val tourId: String? = null,
    val isRunning: Boolean = false,
    val stops: List<StopEntity> = emptyList(),
    val visitedStopIds: Set<String> = emptySet(),
    /** Stop whose narration is loaded or playing. */
    val currentStopId: String? = null,
    /** Stop the walker should be heading towards next. */
    val nextStopId: String? = null,
    /** Set briefly when a geofence fires, so the UI can announce the arrival. */
    val arrivedStopId: String? = null,
    /** True when the current playback was started by a geofence rather than by the user. */
    val lastPlaybackWasAuto: Boolean = false,
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
     * True while the introduction is the active narration. The UI shows it in place of a stop card,
     * and it clears as soon as the walker reaches the first stop.
     */
    val showingOverview: Boolean = false,
) {
    val currentStop: StopEntity? get() = stops.firstOrNull { it.id == currentStopId }
    val nextStop: StopEntity? get() = stops.firstOrNull { it.id == nextStopId }
    val arrivedStop: StopEntity? get() = stops.firstOrNull { it.id == arrivedStopId }
    val progressFraction: Float
        get() = if (stops.isEmpty()) 0f else visitedStopIds.size.toFloat() / stops.size
}
