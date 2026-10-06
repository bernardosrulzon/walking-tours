package com.walkingtours.app.location

import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.util.Geo

/**
 * Decides which stops the walker is currently *inside*.
 *
 * Kept as a pure, dependency-free class so the behaviour is easy to reason about and to test
 * without a device.
 *
 * It answers only "which arrival radii currently contain the walker", using a wider exit radius than
 * entry radius so GPS jitter does not make a stop flicker in and out. It deliberately does **not**
 * decide whether a stop may start playing: that depends on the route order, on which stops have
 * already fired, and on whether narration is running, and it belongs to
 * [com.walkingtours.app.tour.TourSessionManager].
 *
 * Returning the current set rather than "just arrived" edges has a useful consequence: a stop the
 * walker is standing in keeps being reported until they leave, so a trigger held back — because the
 * previous stop was still talking — can fire on the first fix after the audio ends, without the
 * walker having to step out of the radius and back in.
 */
class ArrivalDetector(private val exitHysteresis: Double = 1.7) {

    /** Stops whose (possibly widened) radius currently contains the walker. */
    private val currentlyInside = mutableSetOf<String>()

    fun reset() {
        currentlyInside.clear()
    }

    /**
     * Feed a fresh fix in and return the stops whose arrival radius currently contains the walker,
     * in the order they appear in [stops].
     */
    fun update(stops: List<StopEntity>, lat: Double, lng: Double): List<StopEntity> {
        val inside = mutableListOf<StopEntity>()

        for (stop in stops) {
            val distance = Geo.distanceMeters(lat, lng, stop.lat, stop.lng)
            val enterRadius = stop.triggerRadiusMeters.toDouble()
            val exitRadius = enterRadius * exitHysteresis

            when {
                distance <= enterRadius -> {
                    currentlyInside.add(stop.id)
                    inside += stop
                }
                // Past the wider exit radius: forget the stop so entering again re-arms it.
                distance > exitRadius -> currentlyInside.remove(stop.id)
                // Between the two radii, but already inside from an earlier fix.
                stop.id in currentlyInside -> inside += stop
            }
        }

        return inside
    }

    /** Distance in metres from the walker to a specific stop. */
    fun distanceTo(stop: StopEntity, lat: Double, lng: Double): Double =
        Geo.distanceMeters(lat, lng, stop.lat, stop.lng)
}
