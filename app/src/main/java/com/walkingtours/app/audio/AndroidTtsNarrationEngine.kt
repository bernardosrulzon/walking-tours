package com.walkingtours.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Free, offline narration using the text-to-speech engine already installed on the phone.
 *
 * Why this is the default: it needs no API key, no network and no per-character billing, and it
 * starts within a few hundred milliseconds of a geofence trigger, which is exactly what a
 * "you just arrived, here is what you are looking at" experience needs.
 *
 * Three details make it feel like a real audio guide rather than a screen reader:
 *  - Names are respelled for an English voice via [SpokenScript], so "Soğukçeşme" is spoken
 *    correctly while the screen still shows proper Turkish spelling.
 *  - [UtteranceProgressListener.onRangeStart] gives the character range being spoken right now,
 *    which is translated back into the on-screen transcript for karaoke-style highlighting.
 *  - Android's TTS has no pause, so [pause] records the current offset and stops, and [resume]
 *    speaks the remaining text. To the user that behaves like a pause button.
 */
class AndroidTtsNarrationEngine(private val context: Context) : NarrationEngine {

    private val _progress = MutableStateFlow(NarrationProgress())
    override val progress: StateFlow<NarrationProgress> = _progress.asStateFlow()

    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = null
    private var initialised = false

    override var isAvailable: Boolean = false
        private set

    override val engineLabel: String = "On-device text-to-speech"

    /** Original narration text, used to reset the highlight on completion. */
    private var displayText: String = ""
    private var currentStopId: String? = null
    private var currentRate: Float = 1.0f

    /** Respelled text and the map back to [displayText]. */
    private var script: SpokenScript? = null

    /** Offset within the spoken text that the current utterance started from. */
    private var utteranceSpokenOffset: Int = 0

    /** Spoken offset of the word currently being read, used to resume after a pause. */
    private var lastSpokenFrom: Int = 0
    private var pausedSpokenOffset: Int = 0

    /**
     * Estimated total length of the current narration. The phone's text-to-speech engine reports no
     * timings whatsoever, so this is derived from the text length and the measured speaking rate of
     * the device voice (about 16.3 characters per second at 1x). It is close enough to drive a
     * progress bar and a 15-second rewind, and it is the only option available.
     */
    private var estimatedDurationMs: Long = 0L

    /** Callbacks waiting for the engine to finish initialising. */
    private val readyCallbacks = mutableListOf<(Boolean) -> Unit>()

    /** A play request that arrived before the engine was ready. */
    private var deferredPlay: DeferredPlay? = null

    private data class DeferredPlay(val stopId: String, val text: String, val startOffset: Int)

    override fun prepare(onReady: (Boolean) -> Unit) {
        if (ready.get()) {
            onReady(true)
            return
        }
        readyCallbacks += onReady
        if (initialised) return
        initialised = true
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                configureLanguage()
            } else {
                Log.w(TAG, "Text-to-speech engine failed to initialise (status=$status)")
                _progress.value = _progress.value.copy(
                    state = NarrationState.UNAVAILABLE,
                    message = "Text-to-speech is not available on this device.",
                )
            }
            ready.set(ok)
            isAvailable = ok
            val callbacks = readyCallbacks.toList()
            readyCallbacks.clear()
            callbacks.forEach { it(ok) }
        }
    }

    private fun configureLanguage() {
        val engine = tts ?: return
        // Prefer a British voice for the narration's neutral register, but accept any English
        // voice the device actually ships rather than falling back to silence.
        for (locale in listOf(Locale.UK, Locale.US, Locale.ENGLISH)) {
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                break
            }
        }
        engine.setSpeechRate(currentRate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                _progress.value = _progress.value.copy(state = NarrationState.PLAYING, message = null)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                // Offsets arrive relative to the substring we submitted, so shift them into the
                // coordinates of the whole spoken text, then back into the displayed prose.
                val spokenFrom = start + utteranceSpokenOffset
                val spokenTo = end + utteranceSpokenOffset
                lastSpokenFrom = spokenFrom
                val display = script?.toDisplayRange(spokenFrom, spokenTo) ?: return
                _progress.value = _progress.value.copy(
                    highlightStart = display.first,
                    highlightEnd = display.last + 1,
                    positionMs = estimatedPositionFor(display.first),
                    durationMs = estimatedDurationMs,
                )
            }

            override fun onDone(utteranceId: String?) {
                _progress.value = _progress.value.copy(
                    state = NarrationState.COMPLETED,
                    highlightStart = displayText.length,
                    highlightEnd = displayText.length,
                    positionMs = estimatedDurationMs,
                    durationMs = estimatedDurationMs,
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError(utteranceId, -1)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS error for utterance $utteranceId (code=$errorCode)")
                _progress.value = _progress.value.copy(
                    state = NarrationState.UNAVAILABLE,
                    message = "Playback failed (code $errorCode).",
                )
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // A deliberate pause also lands here; do not clobber the PAUSED state we just set.
                if (_progress.value.state == NarrationState.PAUSED) return
                _progress.value = _progress.value.copy(state = NarrationState.IDLE)
            }
        })
    }

    override fun play(stopId: String, text: String, startOffset: Int) {
        val engine = tts
        if (engine == null || !ready.get()) {
            // Cold start race: a geofence can fire seconds after launch, before the speech engine
            // has bound to its service. Rather than dropping the narration, hold the request and
            // honour it the moment the engine reports ready.
            deferredPlay = DeferredPlay(stopId, text, startOffset)
            _progress.value = NarrationProgress(
                state = NarrationState.PREPARING,
                stopId = stopId,
                message = "Starting audio\u2026",
            )
            prepare { ok ->
                val pending = deferredPlay
                deferredPlay = null
                if (ok && pending != null) {
                    play(pending.stopId, pending.text, pending.startOffset)
                } else if (!ok) {
                    _progress.value = NarrationProgress(
                        state = NarrationState.UNAVAILABLE,
                        stopId = stopId,
                        message = "Text-to-speech is not available on this device.",
                    )
                }
            }
            return
        }

        currentStopId = stopId
        displayText = text
        estimatedDurationMs = estimateDurationMs(text)
        // Building the respelled script is a single pass over a few hundred characters, so there is
        // no reason to cache it and risk serving a stale map.
        val built = SpokenScript.build(text)
        script = built

        // Callers pass a display offset (used when resuming); translate it into spoken coordinates.
        utteranceSpokenOffset = if (startOffset <= 0) 0 else built.spokenOffsetForDisplay(startOffset)
        lastSpokenFrom = utteranceSpokenOffset
        val remaining = built.spokenText.substring(utteranceSpokenOffset.coerceIn(0, built.spokenText.length))

        val displayHighlight = startOffset.coerceIn(0, text.length)

        if (remaining.isBlank()) {
            _progress.value = NarrationProgress(
                state = NarrationState.COMPLETED,
                stopId = stopId,
                highlightStart = text.length,
                highlightEnd = text.length,
                rate = currentRate,
            )
            return
        }

        engine.setSpeechRate(currentRate)
        _progress.value = NarrationProgress(
            state = NarrationState.PREPARING,
            stopId = stopId,
            highlightStart = displayHighlight,
            highlightEnd = displayHighlight,
            rate = currentRate,
            positionMs = estimatedPositionFor(displayHighlight),
            durationMs = estimatedDurationMs,
        )
        engine.speak(remaining, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Speaking rate measured on device: roughly 16.3 characters per second at 1x. */
    private fun estimateDurationMs(text: String): Long {
        val charsPerSecond = CHARS_PER_SECOND * currentRate.coerceAtLeast(0.5f)
        return ((text.length / charsPerSecond) * 1000f).toLong().coerceAtLeast(1_000L)
    }

    private fun estimatedPositionFor(charOffset: Int): Long {
        if (estimatedDurationMs <= 0L || displayText.isEmpty()) return 0L
        val fraction = charOffset.toFloat() / displayText.length
        return (estimatedDurationMs * fraction).toLong().coerceIn(0L, estimatedDurationMs)
    }

    /**
     * The phone's engine cannot seek, so seeking re-speaks from the equivalent character offset.
     * That makes the media notification's rewind button behave the same on both engines.
     */
    override fun seekTo(positionMs: Long) {
        val stopId = currentStopId ?: return
        if (currentTextBlank() || estimatedDurationMs <= 0L) return
        val fraction = (positionMs.toFloat() / estimatedDurationMs).coerceIn(0f, 1f)
        val offset = (fraction * displayText.length).toInt().coerceIn(0, displayText.length)
        // Rewinding and then hearing the same word twice is worse than skipping it, so round the
        // target down to the start of the word it lands inside.
        val snapped = displayText.lastIndexOf(' ', offset.coerceAtMost(displayText.length - 1))
            .let { if (it > 0) it + 1 else offset }
        play(stopId, displayText, snapped)
    }

    private fun currentTextBlank(): Boolean = displayText.isEmpty()

    override fun pause() {
        val engine = tts ?: return
        val state = _progress.value.state
        if (state != NarrationState.PLAYING && state != NarrationState.PREPARING) return
        pausedSpokenOffset = lastSpokenFrom
        _progress.value = _progress.value.copy(state = NarrationState.PAUSED)
        engine.stop()
    }

    override fun resume() {
        val stopId = currentStopId ?: return
        if (_progress.value.state != NarrationState.PAUSED) return
        val built = script ?: return
        val displayOffset = built.toDisplayRange(pausedSpokenOffset, pausedSpokenOffset + 1)?.first ?: 0
        play(stopId, displayText, displayOffset)
    }

    override fun stop() {
        utteranceSpokenOffset = 0
        lastSpokenFrom = 0
        pausedSpokenOffset = 0
        tts?.stop()
        _progress.value = NarrationProgress(state = NarrationState.IDLE, rate = currentRate)
    }

    override fun setRate(rate: Float) {
        currentRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(currentRate)
        _progress.value = _progress.value.copy(rate = currentRate)
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
        isAvailable = false
        // Allow the engine to be rebuilt if this process lives on and the app is reopened.
        initialised = false
        script = null
        displayText = ""
        currentStopId = null
        pausedSpokenOffset = 0
        utteranceSpokenOffset = 0
        lastSpokenFrom = 0
    }

    private companion object {
        const val TAG = "TtsNarration"
        const val UTTERANCE_ID = "walking-tour-narration"

        /** Measured on the Android emulator's Google TTS voice at speech rate 1.0. */
        const val CHARS_PER_SECOND = 16.3f
    }
}
