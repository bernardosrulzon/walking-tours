package com.walkingtours.app.audio

import android.content.Context
import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.GoogleCloudTtsClient

/**
 * Chooses the narration implementation for a given configuration.
 *
 * The app has two engines: the phone's own text-to-speech, which is free and works offline, and the
 * Google Cloud voice, which sounds far better but needs an API key, a network connection the first
 * time a stop is heard, and money. Which one is used is entirely the user's choice in Settings, and
 * this is the single place that decision is made.
 */
object NarrationEngines {

    fun create(
        context: Context,
        settings: AiSettings,
        cloudClient: GoogleCloudTtsClient,
        useCloudVoice: Boolean,
    ): NarrationEngine = if (useCloudVoice && settings.current.hasTtsKey) {
        GoogleCloudTtsNarrationEngine(context, settings, cloudClient)
    } else {
        AndroidTtsNarrationEngine(context)
    }
}
