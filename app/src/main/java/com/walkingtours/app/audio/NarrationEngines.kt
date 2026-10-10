package com.walkingtours.app.audio

import android.content.Context
import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.GeminiTtsClient

/**
 * Chooses the narration implementation for a given configuration.
 *
 * The app has two engines: the phone's own text-to-speech, which is free and works offline, and the
 * Gemini voice, which sounds far better but needs an API key, a network connection the first time a
 * stop is heard, and money. Which one is used is entirely the user's choice in Settings, and this is
 * the single place that decision is made.
 */
object NarrationEngines {

    fun create(
        context: Context,
        settings: AiSettings,
        cloudClient: GeminiTtsClient,
        useCloudVoice: Boolean,
    ): NarrationEngine = if (useCloudVoice && settings.current.hasGeminiKey) {
        GeminiTtsNarrationEngine(context, settings, cloudClient)
    } else {
        AndroidTtsNarrationEngine(context, settings)
    }
}
