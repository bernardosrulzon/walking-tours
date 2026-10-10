package com.walkingtours.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Default cloud voice: a Gemini 3.8 prebuilt voice. */
const val DEFAULT_CLOUD_VOICE = "Kore"

/** Default narration language. */
const val NARRATION_LANGUAGE = "en-US"

/** Brazilian Portuguese narration. */
const val NARRATION_LANGUAGE_PT_BR = "pt-BR"

/** The narration languages the app can speak, with their display labels. */
val NARRATION_LANGUAGES: List<Pair<String, String>> = listOf(
    NARRATION_LANGUAGE to "English",
    NARRATION_LANGUAGE_PT_BR to "Português (BR)",
)

/** The appearance modes the app offers, with their display labels. */
val THEME_MODES: List<Pair<String, String>> = listOf(
    THEME_MODE_SYSTEM to "System",
    THEME_MODE_LIGHT to "Light",
    THEME_MODE_DARK to "Dark",
)

/** Follow the system dark-mode setting. */
const val THEME_MODE_SYSTEM = "system"

/** Always use the light palette. */
const val THEME_MODE_LIGHT = "light"

/** Always use the dark palette. */
const val THEME_MODE_DARK = "dark"

/**
 * User-facing AI configuration.
 *
 * Keys come from `local.properties` at build time via BuildConfig, and can be overridden here at
 * runtime without a rebuild. Nothing is ever written to the repository.
 */
data class AiSettingsState(
    val useCloudVoice: Boolean = false,
    val cloudVoiceName: String = DEFAULT_CLOUD_VOICE,
    val geminiApiKey: String = "",
    /** Blank means "discover the best available model at runtime". */
    val geminiModel: String = "",
    /** Read AI answers aloud through the same narration engine as the tour. */
    val narrationRate: Float = 1.0f,
    /**
     * When true, each chapter (the introduction and every stop) starts playing on its own — the
     * introduction when the tour starts, stops on geofence arrival. When false, chapters only
     * start when the user presses play.
     */
    val autoPlayChapters: Boolean = true,
    /** BCP 47 tag for the narration language: "en-US" or "pt-BR". */
    val narrationLanguage: String = NARRATION_LANGUAGE,
    /** Appearance: "system", "light" or "dark". */
    val themeMode: String = THEME_MODE_SYSTEM,
) {
    val hasGeminiKey: Boolean get() = geminiApiKey.isNotBlank()

    /** True only when the user has both chosen the cloud engine and supplied a usable key. */
    val cloudVoiceReady: Boolean get() = useCloudVoice && hasGeminiKey
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
        val buildGemini = BuildConfigKeys.geminiApiKey
        return AiSettingsState(
            // Default the cloud voice on when a key was supplied at build time, so a configured
            // build just works; otherwise stay on the free on-device engine.
            useCloudVoice = prefs.getBoolean(KEY_USE_CLOUD, buildGemini.isNotBlank()),
            cloudVoiceName = (prefs.getString(KEY_VOICE, null) ?: DEFAULT_CLOUD_VOICE)
                // Drop a Cloud TTS name saved before the switch to Gemini voices.
                .takeIf { it in GEMINI_TTS_VOICES } ?: DEFAULT_CLOUD_VOICE,
            geminiApiKey = prefs.getString(KEY_GEMINI_KEY, null) ?: buildGemini,
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, "") ?: "",
            narrationRate = prefs.getFloat(KEY_RATE, 1.0f),
            autoPlayChapters = prefs.getBoolean(KEY_AUTO_PLAY, true),
            narrationLanguage = (prefs.getString(KEY_NARRATION_LANGUAGE, null) ?: NARRATION_LANGUAGE)
                .takeIf { it in NARRATION_LANGUAGES.map { (tag, _) -> tag } } ?: NARRATION_LANGUAGE,
            themeMode = (prefs.getString(KEY_THEME_MODE, null) ?: THEME_MODE_SYSTEM)
                .takeIf { it in THEME_MODES.map { (tag, _) -> tag } } ?: THEME_MODE_SYSTEM,
        )
    }

    fun update(transform: (AiSettingsState) -> AiSettingsState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putBoolean(KEY_USE_CLOUD, next.useCloudVoice)
            .putString(KEY_VOICE, next.cloudVoiceName)
            .putString(KEY_GEMINI_KEY, next.geminiApiKey)
            .putString(KEY_GEMINI_MODEL, next.geminiModel)
            .putFloat(KEY_RATE, next.narrationRate)
            .putBoolean(KEY_AUTO_PLAY, next.autoPlayChapters)
            .putString(KEY_NARRATION_LANGUAGE, next.narrationLanguage)
            .putString(KEY_THEME_MODE, next.themeMode)
            .apply()
    }

    private companion object {
        const val KEY_USE_CLOUD = "use_cloud_voice"
        const val KEY_VOICE = "cloud_voice_name"
        const val KEY_GEMINI_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_RATE = "narration_rate"
        const val KEY_AUTO_PLAY = "auto_play_chapters"
        const val KEY_NARRATION_LANGUAGE = "narration_language"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

/** Indirection so this file does not depend on the generated BuildConfig directly. */
object BuildConfigKeys {
    val geminiApiKey: String get() = com.walkingtours.app.BuildConfig.GOOGLE_GEMINI_API_KEY
}
