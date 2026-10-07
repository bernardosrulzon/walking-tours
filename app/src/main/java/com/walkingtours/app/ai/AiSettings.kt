package com.walkingtours.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Default cloud voice: a Gemini 3.8 prebuilt voice. */
const val DEFAULT_CLOUD_VOICE = "Kore"

/** Language used for both narration and voice discovery. */
const val NARRATION_LANGUAGE = "en-US"

/**
 * User-facing AI configuration.
 *
 * Keys come from `local.properties` at build time via BuildConfig, and can be overridden here at
 * runtime without a rebuild. Nothing is ever written to the repository.
 */
data class AiSettingsState(
    val useCloudVoice: Boolean = false,
    val cloudVoiceName: String = DEFAULT_CLOUD_VOICE,
    val ttsApiKey: String = "",
    val geminiApiKey: String = "",
    /** Blank means "discover the best available model at runtime". */
    val geminiModel: String = "",
    val speakAiAnswers: Boolean = false,
    /** Read AI answers aloud through the same narration engine as the tour. */
    val narrationRate: Float = 1.0f,
    /**
     * When true, each chapter (the introduction and every stop) starts playing on its own — the
     * introduction when the tour starts, stops on geofence arrival. When false, chapters only
     * start when the user presses play.
     */
    val autoPlayChapters: Boolean = true,
) {
    val hasTtsKey: Boolean get() = ttsApiKey.isNotBlank()
    val hasGeminiKey: Boolean get() = geminiApiKey.isNotBlank()

    /** True when either key could drive the Gemini cloud voice. */
    val hasCloudVoiceKey: Boolean get() = hasTtsKey || hasGeminiKey

    /** True only when the user has both chosen the cloud engine and supplied a usable key. */
    val cloudVoiceReady: Boolean get() = useCloudVoice && hasCloudVoiceKey
}

/**
 * Persists the AI settings and exposes them as a flow the engines and UI can observe.
 *
 * Deliberately small and dependency-free: this is the only mutable configuration in the app, and
 * SharedPreferences is the right tool at this size.
 */
class AiSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AiSettingsState> = _state.asStateFlow()

    val current: AiSettingsState get() = _state.value

    private fun load(): AiSettingsState {
        val buildTts = BuildConfigKeys.ttsApiKey
        val buildGemini = BuildConfigKeys.geminiApiKey
        return AiSettingsState(
            // Default the cloud voice on when a key was supplied at build time, so a configured
            // build just works; otherwise stay on the free on-device engine.
            useCloudVoice = prefs.getBoolean(KEY_USE_CLOUD, buildTts.isNotBlank() || buildGemini.isNotBlank()),
            cloudVoiceName = (prefs.getString(KEY_VOICE, null) ?: DEFAULT_CLOUD_VOICE)
                // Drop a Cloud TTS name saved before the switch to Gemini voices.
                .takeIf { it in GEMINI_TTS_VOICES } ?: DEFAULT_CLOUD_VOICE,
            ttsApiKey = prefs.getString(KEY_TTS_KEY, null) ?: buildTts,
            geminiApiKey = prefs.getString(KEY_GEMINI_KEY, null) ?: buildGemini,
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, "") ?: "",
            speakAiAnswers = prefs.getBoolean(KEY_SPEAK_ANSWERS, false),
            narrationRate = prefs.getFloat(KEY_RATE, 1.0f),
            autoPlayChapters = prefs.getBoolean(KEY_AUTO_PLAY, true),
        )
    }

    fun update(transform: (AiSettingsState) -> AiSettingsState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putBoolean(KEY_USE_CLOUD, next.useCloudVoice)
            .putString(KEY_VOICE, next.cloudVoiceName)
            .putString(KEY_TTS_KEY, next.ttsApiKey)
            .putString(KEY_GEMINI_KEY, next.geminiApiKey)
            .putString(KEY_GEMINI_MODEL, next.geminiModel)
            .putBoolean(KEY_SPEAK_ANSWERS, next.speakAiAnswers)
            .putFloat(KEY_RATE, next.narrationRate)
            .putBoolean(KEY_AUTO_PLAY, next.autoPlayChapters)
            .apply()
    }

    private companion object {
        const val KEY_USE_CLOUD = "use_cloud_voice"
        const val KEY_VOICE = "cloud_voice_name"
        const val KEY_TTS_KEY = "tts_api_key"
        const val KEY_GEMINI_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_SPEAK_ANSWERS = "speak_ai_answers"
        const val KEY_RATE = "narration_rate"
        const val KEY_AUTO_PLAY = "auto_play_chapters"
    }
}

/** Indirection so this file does not depend on the generated BuildConfig directly. */
object BuildConfigKeys {
    val ttsApiKey: String get() = com.walkingtours.app.BuildConfig.GOOGLE_TTS_API_KEY
    val geminiApiKey: String get() = com.walkingtours.app.BuildConfig.GOOGLE_GEMINI_API_KEY
}
