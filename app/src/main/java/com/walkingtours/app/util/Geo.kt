package com.walkingtours.app.util

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesic helpers built on the haversine formula. Accuracy is well within a metre at the
 * distances a walking tour cares about, and it needs no Google Play services.
 */
object Geo {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Great-circle distance between two points, in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing from point 1 to point 2, in degrees clockwise from true north. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Turns a bearing into a spoken-style compass point, e.g. "north-east". */
    fun compassDirection(bearing: Double): String {
        val points = listOf(
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west",
        )
        val index = ((bearing + 22.5) / 45.0).toInt() % 8
        return points[index]
    }

    /** Human-friendly distance: "45 m" up close, "1.2 km" further out. */
    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        else -> String.format("%.1f km", meters / 1000.0)
    }

    /**
     * Walking time using a relaxed sightseeing pace of 4.5 km/h, which is slower than the
     * 5 km/h used for pure transit because people stop to look at things.
     */
    fun walkingMinutes(meters: Double, metersPerSecond: Double = 1.25): Int =
        (meters / metersPerSecond / 60.0).roundToInt().coerceAtLeast(1)
}
