package com.walkingtours.app.ai

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** One voice offered by Google Cloud Text-to-Speech. */
data class CloudVoice(
    val name: String,
    val languageCodes: List<String>,
    val gender: String,
) {
    /** Chirp 3: HD voices are the newest, most natural generation. */
    val isChirp3Hd: Boolean get() = name.contains("Chirp3", ignoreCase = true)

    /** Short label for the settings list, e.g. "Achernar" from "en-US-Chirp3-HD-Achernar". */
    val shortName: String get() = name.substringAfterLast('-')

    val family: String
        get() = when {
            name.contains("Chirp3-HD", true) -> "Chirp 3: HD"
            name.contains("Neural2", true) -> "Neural2"
            name.contains("Studio", true) -> "Studio"
            name.contains("Wavenet", true) -> "WaveNet"
            name.contains("Standard", true) -> "Standard"
            else -> "Other"
        }
}

/**
 * Google Cloud Text-to-Speech, v1 REST API.
 *
 * Two calls matter to the app: [listVoices] to populate the voice picker, and [synthesize] to turn a
 * stop's narration into audio. Both are ordinary API-key calls, and both are sent with the
 * Android app identity headers so a key restricted to this package and signing certificate works.
 *
 * Note on speaking rate: Chirp 3: HD voices reject `speakingRate` and `pitch`. Speed changes for
 * those voices are applied at playback time instead, which is better anyway because it does not
 * invalidate the cached audio.
 */
class GoogleCloudTtsClient(
    private val context: Context,
    private val apiKey: () -> String,
) {

    suspend fun listVoices(languageCode: String = NARRATION_LANGUAGE): List<CloudVoice> {
        val key = requireKey()
        val url = "$BASE_URL/voices?languageCode=$languageCode&key=$key"
        val json = AiHttp.getJson(url, AppIdentityHeaders.build(context))
        val array = json.optJSONArray("voices") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val voice = array.optJSONObject(i) ?: continue
                val name = voice.optString("name")
                if (name.isBlank()) continue
                val codes = voice.optJSONArray("languageCodes")?.let { codes ->
                    (0 until codes.length()).map { codes.optString(it) }
                }.orEmpty()
                add(
                    CloudVoice(
                        name = name,
                        languageCodes = codes,
                        gender = voice.optString("ssmlGender", ""),
                    ),
                )
            }
        }
    }

    /**
     * Synthesises [text] and returns encoded audio bytes (MP3).
     */
    suspend fun synthesize(
        text: String,
        voiceName: String,
        languageCode: String = NARRATION_LANGUAGE,
    ): ByteArray {
        val key = requireKey()
        val body = JSONObject().apply {
            put("input", JSONObject().put("text", text))
            put(
                "voice",
                JSONObject().apply {
                    put("languageCode", languageCode)
                    put("name", voiceName)
                },
            )
            put(
                "audioConfig",
                JSONObject().apply {
                    put("audioEncoding", "MP3")
                    // Chirp 3: HD rejects these, so only send them for other voice families.
                    if (!voiceName.contains("Chirp3", ignoreCase = true)) {
                        put("speakingRate", 1.0)
                        put("pitch", 0.0)
                    }
                },
            )
        }

        val json = AiHttp.postJson("$BASE_URL/text:synthesize?key=$key", body, AppIdentityHeaders.build(context))
        val encoded = json.optString("audioContent")
        if (encoded.isBlank()) {
            throw AiException("Google returned no audio for this voice. Try a different voice.")
        }
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    private fun requireKey(): String {
        val key = apiKey()
        if (key.isBlank()) {
            throw AiException("No Google Cloud Text-to-Speech API key is configured.")
        }
        return key
    }

    private companion object {
        const val BASE_URL = "https://texttospeech.googleapis.com/v1"
    }
}
