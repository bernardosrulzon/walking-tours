package com.walkingtours.app.util

/** Shared display formatting so durations and distances read the same everywhere in the app. */
object Formatters {

    /** Minutes as "45 min", "2 h", or "3 h 15". */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60}"
    }

    /** A tour's total commitment: time on your feet plus time spent at the stops. */
    fun totalExperience(walkMinutes: Int, stopMinutes: Int): String =
        duration(walkMinutes + stopMinutes)

    /** Player-style clock: "0:07", "1:23", "12:05". Used either side of the transport slider. */
    fun clock(millis: Long): String {
        val safe = millis.coerceAtLeast(0L) / 1000
        val minutes = safe / 60
        val seconds = safe % 60
        return "%d:%02d".format(minutes, seconds)
    }
}
