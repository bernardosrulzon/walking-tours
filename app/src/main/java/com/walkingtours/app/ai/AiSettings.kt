package com.walkingtours.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Default cloud voice: a Chirp 3: HD British voice, the most natural option Google offers. */
const val DEFAULT_CLOUD_VOICE = "en-GB-Chirp3-HD-Achernar"

/** Language used for both narration and voice discovery. */
const val NARRATION_LANGUAGE = "en-GB"

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
) {
    val hasTtsKey: Boolean get() = ttsApiKey.isNotBlank()
    val hasGeminiKey: Boolean get() = geminiApiKey.isNotBlank()

    /** True only when the user has both chosen the cloud engine and supplied a usable key. */
    val cloudVoiceReady: Boolean get() = useCloudVoice && hasTtsKey
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
            useCloudVoice = prefs.getBoolean(KEY_USE_CLOUD, buildTts.isNotBlank()),
            cloudVoiceName = prefs.getString(KEY_VOICE, DEFAULT_CLOUD_VOICE) ?: DEFAULT_CLOUD_VOICE,
            ttsApiKey = prefs.getString(KEY_TTS_KEY, null) ?: buildTts,
            geminiApiKey = prefs.getString(KEY_GEMINI_KEY, null) ?: buildGemini,
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, "") ?: "",
            speakAiAnswers = prefs.getBoolean(KEY_SPEAK_ANSWERS, false),
            narrationRate = prefs.getFloat(KEY_RATE, 1.0f),
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
    }
}

/** Indirection so this file does not depend on the generated BuildConfig directly. */
object BuildConfigKeys {
    val ttsApiKey: String get() = com.walkingtours.app.BuildConfig.GOOGLE_TTS_API_KEY
    val geminiApiKey: String get() = com.walkingtours.app.BuildConfig.GOOGLE_GEMINI_API_KEY
}
