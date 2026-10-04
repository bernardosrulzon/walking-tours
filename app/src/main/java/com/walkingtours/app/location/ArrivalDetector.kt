package com.walkingtours.app.location

import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.util.Geo

/**
 * Decides when the walker has *arrived* at a stop.
 *
 * Kept as a pure, dependency-free class so the behaviour is easy to reason about and to test
 * without a device.
 *
 * Two problems it solves:
 *  1. **Repeat triggering.** Standing at a stop must not restart the narration every few seconds.
 *     A stop fires once, then stays quiet until the walker leaves and comes back.
 *  2. **GPS jitter.** Raw fixes wander by 10-30 m in dense old-city streets, which would otherwise
 *     make a stop flicker in and out of range. So re-arming requires leaving a *wider* radius than
 *     the one that triggered the arrival.
 */
class ArrivalDetector(private val exitHysteresis: Double = 1.7) {

    /** Stops whose radius currently contains the walker. */
    private val currentlyInside = mutableSetOf<String>()

    /** Stops that have already fired and not yet re-armed. */
    private val alreadyFired = mutableSetOf<String>()

    fun reset() {
        currentlyInside.clear()
        alreadyFired.clear()
    }

    /** Suppress triggering for a stop the user opened manually from the list. */
    fun suppress(stopId: String) {
        alreadyFired.add(stopId)
    }

    /**
     * Feed a fresh fix in. Returns the stop the walker has just arrived at, or null.
     * If several radii overlap, the closest one wins.
     */
    fun update(stops: List<StopEntity>, lat: Double, lng: Double): StopEntity? {
        val arrivals = mutableListOf<Pair<StopEntity, Double>>()

        for (stop in stops) {
            val distance = Geo.distanceMeters(lat, lng, stop.lat, stop.lng)
            val enterRadius = stop.triggerRadiusMeters.toDouble()
            val exitRadius = enterRadius * exitHysteresis

            if (distance <= enterRadius) {
                val wasInside = !currentlyInside.add(stop.id)
                if (!wasInside && alreadyFired.add(stop.id)) {
                    arrivals += stop to distance
                }
            } else if (distance > exitRadius) {
                currentlyInside.remove(stop.id)
                alreadyFired.remove(stop.id)
            }
        }

        return arrivals.minByOrNull { it.second }?.first
    }

    /** Distance in metres from the walker to a specific stop. */
    fun distanceTo(stop: StopEntity, lat: Double, lng: Double): Double =
        Geo.distanceMeters(lat, lng, stop.lat, stop.lng)
}
