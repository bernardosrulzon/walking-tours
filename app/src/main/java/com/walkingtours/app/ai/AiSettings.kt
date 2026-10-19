package com.walkingtours.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Default cloud voice: a Gemini 3.8 prebuilt voice. */
const val DEFAULT_CLOUD_VOICE = "Kore"

/** The text-model providers: Google Gemini (default) or OpenCode. */
const val MODEL_PROVIDER_OPENCODE = "opencode"
const val MODEL_PROVIDER_GOOGLE = "google"

/** The text-model providers, with the names shown in Settings. */
val MODEL_PROVIDERS: List<Pair<String, String>> = listOf(
    MODEL_PROVIDER_OPENCODE to "OpenCode Go",
    MODEL_PROVIDER_GOOGLE to "Google Gemini",
)

/** The two cloud speech providers the walker can choose between. */
const val TTS_ENGINE_GEMINI = "gemini"
const val TTS_ENGINE_ELEVENLABS = "elevenlabs"

/** The cloud voice engines, with the names shown in Settings. */
val TTS_ENGINES: List<Pair<String, String>> = listOf(
    TTS_ENGINE_GEMINI to "Gemini 3.8 Flash",
    TTS_ENGINE_ELEVENLABS to "ElevenLabs v4",
)

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
    /** Which service writes the guides, rewrites and chat: OpenCode Go (default) or Google. */
    val modelProvider: String = MODEL_PROVIDER_OPENCODE,
    /** Which cloud speech provider reads the narration: Gemini TTS or ElevenLabs. */
    val ttsEngine: String = TTS_ENGINE_GEMINI,
    val cloudVoiceName: String = DEFAULT_CLOUD_VOICE,
    val geminiApiKey: String = "",
    val elevenLabsApiKey: String = "",
    val opencodeApiKey: String = "",
    /** Stable per-install id used as the OpenCode routing/caching session prefix. */
    val installId: String = "",
    /** Read AI answers aloud through the same narration engine as the tour. */
    val narrationRate: Float = 1.0f,
    /**
     * When true, each chapter (the introduction and every stop) starts playing on its own — the
     * introduction when the tour starts, stops on geofence arrival. When false, chapters only
     * start when the user presses play.
     */
    val autoPlayChapters: Boolean = false,
    /** BCP 47 tag for the narration language: "en-US" or "pt-BR". */
    val narrationLanguage: String = NARRATION_LANGUAGE,
    /** Appearance: "system", "light" or "dark". */
    val themeMode: String = THEME_MODE_SYSTEM,
) {
    val hasGeminiKey: Boolean get() = geminiApiKey.isNotBlank()

    val hasElevenLabsKey: Boolean get() = elevenLabsApiKey.isNotBlank()

    val hasOpenCodeKey: Boolean get() = opencodeApiKey.isNotBlank()

    /** True when the selected text-model provider has the key it needs. */
    val modelProviderReady: Boolean
        get() = when (modelProvider) {
            MODEL_PROVIDER_OPENCODE -> hasOpenCodeKey
            else -> hasGeminiKey
        }

    /** True when the selected cloud speaker has the key it needs. */
    val cloudEngineReady: Boolean
        get() = when (ttsEngine) {
            TTS_ENGINE_ELEVENLABS -> hasElevenLabsKey
            else -> hasGeminiKey
        }

    /** True only when the user has both chosen the cloud engine and supplied a usable key. */
    val cloudVoiceReady: Boolean get() = useCloudVoice && cloudEngineReady
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
        val buildEleven = BuildConfigKeys.elevenLabsApiKey
        val buildOpenCode = BuildConfigKeys.openCodeApiKey
        // The install id is generated once and kept: OpenCode uses the session header for routing
        // and prompt caching, so it must not change between requests.
        val installId = prefs.getString(KEY_INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_INSTALL_ID, it).apply() }
        return AiSettingsState(
            // Default the cloud voice on when a key was supplied at build time, so a configured
            // build just works; otherwise stay on the free on-device engine.
            useCloudVoice = prefs.getBoolean(
                KEY_USE_CLOUD,
                buildGemini.isNotBlank() || buildEleven.isNotBlank(),
            ),
            // OpenCode Go is the default when its key is present; a build without one still works
            // on Google Gemini out of the box.
            modelProvider = (prefs.getString(KEY_MODEL_PROVIDER, null) ?: defaultModelProvider(buildOpenCode))
                .takeIf { it in MODEL_PROVIDERS.map { (id, _) -> id } }
                ?: defaultModelProvider(buildOpenCode),
            ttsEngine = (prefs.getString(KEY_TTS_ENGINE, null) ?: TTS_ENGINE_GEMINI)
                .takeIf { it in TTS_ENGINES.map { (id, _) -> id } } ?: TTS_ENGINE_GEMINI,
            cloudVoiceName = (prefs.getString(KEY_VOICE, null) ?: DEFAULT_CLOUD_VOICE)
                // Drop a Cloud TTS name saved before the switch to Gemini voices.
                .takeIf { it in GEMINI_TTS_VOICES } ?: DEFAULT_CLOUD_VOICE,
            geminiApiKey = prefs.getString(KEY_GEMINI_KEY, null) ?: buildGemini,
            elevenLabsApiKey = prefs.getString(KEY_ELEVENLABS_KEY, null) ?: buildEleven,
            opencodeApiKey = prefs.getString(KEY_OPENCODE_KEY, null) ?: buildOpenCode,
            installId = installId,
            narrationRate = prefs.getFloat(KEY_RATE, 1.0f),
            autoPlayChapters = prefs.getBoolean(KEY_AUTO_PLAY, false),
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
            .putString(KEY_MODEL_PROVIDER, next.modelProvider)
            .putString(KEY_TTS_ENGINE, next.ttsEngine)
            .putString(KEY_VOICE, next.cloudVoiceName)
            .putString(KEY_GEMINI_KEY, next.geminiApiKey)
            .putString(KEY_ELEVENLABS_KEY, next.elevenLabsApiKey)
            .putString(KEY_OPENCODE_KEY, next.opencodeApiKey)
            .putFloat(KEY_RATE, next.narrationRate)
            .putBoolean(KEY_AUTO_PLAY, next.autoPlayChapters)
            .putString(KEY_NARRATION_LANGUAGE, next.narrationLanguage)
            .putString(KEY_THEME_MODE, next.themeMode)
            .apply()
    }

    private companion object {
        const val KEY_USE_CLOUD = "use_cloud_voice"
        const val KEY_MODEL_PROVIDER = "model_provider"
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_VOICE = "cloud_voice_name"
        const val KEY_GEMINI_KEY = "gemini_api_key"
        const val KEY_ELEVENLABS_KEY = "elevenlabs_api_key"
        const val KEY_OPENCODE_KEY = "opencode_api_key"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_RATE = "narration_rate"
        const val KEY_AUTO_PLAY = "auto_play_chapters"
        const val KEY_NARRATION_LANGUAGE = "narration_language"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

/** OpenCode Go is the default when the build has a key for it; Google otherwise. */
private fun defaultModelProvider(openCodeKey: String): String =
    if (openCodeKey.isNotBlank()) MODEL_PROVIDER_OPENCODE else MODEL_PROVIDER_GOOGLE

/** Indirection so this file does not depend on the generated BuildConfig directly. */
object BuildConfigKeys {
    val geminiApiKey: String get() = com.walkingtours.app.BuildConfig.GOOGLE_GEMINI_API_KEY
    val elevenLabsApiKey: String get() = com.walkingtours.app.BuildConfig.ELEVENLABS_API_KEY
    val openCodeApiKey: String get() = com.walkingtours.app.BuildConfig.OPENCODE_API_KEY
}
