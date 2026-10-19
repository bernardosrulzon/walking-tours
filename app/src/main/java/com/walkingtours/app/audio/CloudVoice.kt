package com.walkingtours.app.audio

import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.GeminiTtsClient
import com.walkingtours.app.ai.Guide

/**
 * One cloud speech provider: the thing that turns a narration into bytes of audio.
 *
 * The player, the cache, the progress flow and the transcript highlighting are provider-agnostic;
 * the only things that differ between Gemini and ElevenLabs are the request, the file format, and
 * what belongs in the cache key. Keeping the seam here is what makes the Settings toggle a real
 * choice rather than two parallel engines drifting apart.
 */
internal interface CloudVoice {

    /** Stable id, part of the cache key and the engine identity. */
    val id: String

    /** Shown in Settings and on the player, e.g. "ElevenLabs · eleven_v4". */
    val label: String

    val isAvailable: Boolean

    /** Extension for [synthesize]'s bytes: "wav" for Gemini, "mp3" for ElevenLabs. */
    val extension: String

    /** Shown when the provider cannot play at all. */
    val unavailableMessage: String

    /** Everything that changes the audio, so a cache hit is always the same sound. */
    fun cacheKey(text: String, style: String?, guide: Guide?): String

    /** The whole narration as one audio file. */
    suspend fun synthesize(text: String, style: String?, language: String, guide: Guide?): ByteArray
}

/**
 * Bumped when the way audio is prepared changes — the text sent, the voice mapping, the cache itself.
 * Part of every provider's key, so old audio is never replayed under a new recipe.
 */
internal const val AUDIO_CACHE_VERSION = "voice-v6"

/** Gemini 3.8 Flash TTS, pinned exactly as it was before the provider seam existed. */
internal class GeminiCloudVoice(
    private val settings: AiSettings,
    private val client: GeminiTtsClient,
) : CloudVoice {

    override val id = "gemini"
    override val extension = "wav"
    override val isAvailable: Boolean get() = settings.current.hasGeminiKey

    private val voice: String get() = client.normalizeVoice(settings.current.cloudVoiceName)

    override val label: String get() = "Gemini voice \u00b7 $voice"

    override val unavailableMessage: String
        get() = "Add a Gemini API key in Settings to use the cloud voice."

    override fun cacheKey(text: String, style: String?, guide: Guide?): String =
        "$id|$voice|${settings.current.narrationLanguage}|${style.orEmpty()}|$AUDIO_CACHE_VERSION|" +
            TtsText.sanitizeForCloud(text)

    override suspend fun synthesize(
        text: String,
        style: String?,
        language: String,
        guide: Guide?,
    ): ByteArray = client.synthesize(
        TtsText.sanitizeForCloud(text),
        voice,
        style,
        language,
    )
}
