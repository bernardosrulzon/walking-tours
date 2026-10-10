package com.walkingtours.app.audio

import android.content.Context
import android.util.Log
import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.GeminiTtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A single, stable [NarrationEngine] that forwards to whichever engine the user has chosen.
 *
 * The rest of the app — the geofence triggers, the transcript highlighting, the session — holds one
 * engine for the whole process lifetime. Swapping the implementation underneath would otherwise mean
 * every screen re-subscribing to a new progress flow mid-tour, so the router owns one flow and
 * re-points it when the choice changes.
 *
 * It also makes the cloud voice safe to use on a walk. Google Cloud is faster and far better
 * sounding, but it needs a network, a valid key and a non-zero quota. If any of those fail — a dead
 * spot, an expired key, an exhausted quota — the router silently finishes the rest of the tour on
 * the phone's own voice rather than leaving the walker standing in silence. That fallback is
 * deliberate: a guide that stops talking is worse than a guide with a plainer accent.
 */
class NarrationRouter(
    private val context: Context,
    private val settings: AiSettings,
    private val cloudClient: GeminiTtsClient,
) : NarrationEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow(NarrationProgress())
    override val progress: StateFlow<NarrationProgress> = _progress.asStateFlow()

    private val deviceEngine = AndroidTtsNarrationEngine(context, settings)
    private var cloudEngine: GeminiTtsNarrationEngine? = null

    private var progressJob: Job? = null

    /** Set when the cloud voice has failed and we have switched to the phone's voice. */
    private var usingDeviceFallback = false

    /** Identifies the current configuration, so we only rebuild when the choice really changed. */
    private var activeKey: String = ""

    private var lastRequest: Request? = null

    private data class Request(val stopId: String, val text: String, val startOffset: Int, val style: String?)

    private val cloudConfigured: Boolean get() = settings.current.cloudVoiceReady

    private val active: NarrationEngine
        get() = if (cloudConfigured && !usingDeviceFallback) {
            cloudEngine ?: deviceEngine
        } else {
            deviceEngine
        }

    init {
        deviceEngine.prepare { }
        bind()
    }

    /** True while the cloud voice is unavailable and the phone's voice is covering for it. */
    val isUsingFallback: Boolean get() = usingDeviceFallback

    override val isAvailable: Boolean get() = active.isAvailable

    override val engineLabel: String
        get() = when {
            cloudConfigured && !usingDeviceFallback -> active.engineLabel
            cloudConfigured -> "${active.engineLabel} \u00b7 cloud voice unavailable"
            else -> active.engineLabel
        }

    /**
     * Re-point at the engine the settings now ask for. Cheap and idempotent: does nothing unless the
     * choice actually changed. The API key is part of the identity, so pasting a corrected key
     * clears an earlier fallback and tries the cloud voice again.
     */
    fun refreshFromSettings() {
        val state = settings.current
        val desiredKey = if (state.cloudVoiceReady) {
            "cloud:${state.cloudVoiceName}:${state.geminiApiKey.hashCode()}:${state.narrationLanguage}"
        } else {
            "device:${state.narrationLanguage}"
        }
        if (desiredKey == activeKey) return

        activeKey = desiredKey
        usingDeviceFallback = false
        active.stop()

        cloudEngine?.release()
        cloudEngine = if (state.cloudVoiceReady) {
            GeminiTtsNarrationEngine(context, settings, cloudClient)
        } else {
            null
        }

        // The phone's voice bakes its locale when it is configured, so a language change has to
        // reach it directly; the cloud engine reads the language per request.
        deviceEngine.refreshLanguage()

        _progress.value = NarrationProgress()
        bind()
    }

    private fun bind() {
        progressJob?.cancel()
        val engine = active
        progressJob = scope.launch {
            engine.progress.collect { update ->
                _progress.value = update
                // Cloud failed for this utterance: cover for it with the on-device voice.
                if (update.state == NarrationState.UNAVAILABLE &&
                    engine is GeminiTtsNarrationEngine &&
                    !usingDeviceFallback
                ) {
                    // Launch separately: bind() cancels the job this collector runs in.
                    scope.launch { fallBackToDevice(update.message) }
                }
            }
        }
    }

    private fun fallBackToDevice(reason: String?) {
        val request = lastRequest ?: return
        Log.w(TAG, "Cloud voice unavailable (${reason.orEmpty().take(120)}); using the on-device voice")
        usingDeviceFallback = true
        cloudEngine?.stop()
        _progress.value = NarrationProgress(
            state = NarrationState.PREPARING,
            stopId = request.stopId,
            message = "Cloud voice unavailable \u2014 using the phone's voice.",
        )
        bind()
        active.play(request.stopId, request.text, request.startOffset, request.style)
    }

    override fun prepare(onReady: (Boolean) -> Unit) = active.prepare(onReady)

    override fun play(stopId: String, text: String, startOffset: Int, style: String?) {
        lastRequest = Request(stopId, text, startOffset, style)
        active.play(stopId, text, startOffset, style)
    }

    override fun pause() = active.pause()

    override fun resume() = active.resume()

    override fun stop() = active.stop()

    override fun seekTo(positionMs: Long) = active.seekTo(positionMs)

    override fun setRate(rate: Float) = active.setRate(rate)

    override fun release() {
        progressJob?.cancel()
        deviceEngine.release()
        cloudEngine?.release()
    }

    private companion object {
        const val TAG = "NarrationRouter"
    }
}
