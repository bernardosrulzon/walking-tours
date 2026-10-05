package com.walkingtours.app.tour

/**
 * How a tour was entered.
 *
 * A tour is one introduction followed by N stops, so there are only four ways in. Every screen
 * passes one of these — the tour detail buttons, a tapped stop, a deep link — instead of each
 * deriving the intent for itself from stop ids and flags. The session turns an entry into a single
 * landing page, and the stop screen turns that page into a pager position.
 */
sealed interface TourEntry {

    /** Start tour: the introduction plays, then the geofences take over. */
    data object Introduction : TourEntry

    /** Start over: as [Introduction], but completed stops are wiped first. */
    data object StartOver : TourEntry

    /** Resume: continue at the first stop still to see. */
    data object Resume : TourEntry

    /**
     * Open one stop. It starts the tour there if none is running, and otherwise just switches to it;
     * either way nothing is recorded as reached — choosing to look at a stop is not standing at it.
     */
    data class Stop(val stopId: String) : TourEntry

    /** True for the two entries whose first page is the spoken introduction. */
    val beginsAtIntroduction: Boolean
        get() = this is Introduction || this is StartOver

    /**
     * Route-argument form. Readable on purpose: these strings end up in logs and saved back stacks,
     * and anything that is not one of the three named entries is a stop id.
     */
    fun encode(): String = when (this) {
        Introduction -> INTRO
        StartOver -> OVER
        Resume -> RESUME
        is Stop -> stopId
    }

    companion object {
        const val INTRO = "intro"
        const val OVER = "over"
        const val RESUME = "resume"

        fun decode(raw: String?): TourEntry = when (raw) {
            null, "", RESUME -> Resume
            INTRO -> Introduction
            OVER -> StartOver
            else -> Stop(raw)
        }
    }
}
