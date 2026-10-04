package com.walkingtours.app.audio

import kotlinx.coroutines.flow.StateFlow

/** Where the narrator currently is. */
enum class NarrationState { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, UNAVAILABLE }

/**
 * Progress plus, crucially, the character range currently being spoken. The range is what lets the
 * stop screen highlight the transcript in time with the audio, karaoke style.
 *
 * The offsets are in the coordinates of the **original narration text**, not of whatever respelled
 * string was handed to the speech engine, so they can be applied straight to the visible transcript.
 * See [SpokenScript] for how that translation is done.
 */
data class NarrationProgress(
    val state: NarrationState = NarrationState.IDLE,
    val stopId: String? = null,
    /** Inclusive start offset into the text that was handed to [NarrationEngine.play]. */
    val highlightStart: Int = 0,
    /** Exclusive end offset. */
    val highlightEnd: Int = 0,
    val rate: Float = 1.0f,
    val message: String? = null,
    /**
     * Playback position and total length, used by the media notification's progress bar.
     *
     * For the cloud voice these are exact, straight from the player. For the phone's own
     * text-to-speech engine no timings exist at all, so both are derived from the character range
     * being spoken and the measured speaking rate — close enough for a seek bar, and it keeps the
     * notification honest on both engines.
     */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    /** 0f..1f, for progress indicators. Zero when the length is not yet known. */
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The audio seam.
 *
 * Everything above this interface — the geofence triggers, the transcript highlighting, the tour
 * session — is written against [NarrationEngine] only. That is deliberate: swapping the free
 * on-device text-to-speech engine for a paid, studio-quality cloud voice later means adding one
 * implementation and changing one line in [NarrationEngines], with no changes anywhere else.
 */
interface NarrationEngine {

    val progress: StateFlow<NarrationProgress>

    /** True once the underlying engine has initialised successfully. */
    val isAvailable: Boolean

    /** Shown in the UI so the user knows what is reading to them. */
    val engineLabel: String

    /** Warm up the engine (voices, language packs). Safe to call repeatedly. */
    fun prepare(onReady: (Boolean) -> Unit = {})

    /**
     * Speak [text] for [stopId], replacing anything already playing.
     * [startOffset] supports resuming a paused narration part-way through.
     */
    fun play(stopId: String, text: String, startOffset: Int = 0)

    fun pause()

    fun resume()

    fun stop()

    /**
     * Jump to [positionMs] within the current narration.
     *
     * The cloud voice seeks its player directly. The phone's own text-to-speech cannot seek, so it
     * re-speaks from the equivalent character offset — which is what makes the 15-second rewind
     * button work identically on both engines.
     */
    fun seekTo(positionMs: Long)

    /** Convenience for the media notification's rewind and fast-forward buttons. */
    fun skipBy(deltaMs: Long) {
        seekTo((progress.value.positionMs + deltaMs).coerceAtLeast(0L))
    }

    fun setRate(rate: Float)

    fun release()
}
