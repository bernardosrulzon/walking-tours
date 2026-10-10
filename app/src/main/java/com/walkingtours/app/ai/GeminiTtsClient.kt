package com.walkingtours.app.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** The prebuilt Gemini TTS voices, shared with the settings picker and the stale-voice guard. */
val GEMINI_TTS_VOICES: List<String> = listOf(
    "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr",
    "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome",
    "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux",
    "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager",
    "Sulafat",
)

/**
 * Documented gender of each prebuilt voice, from the voice table in AI Studio's speech generation
 * docs. A guide must never sound like the wrong person, so this is what the voice is matched
 * against — keep it in step with that table if voices are added.
 */
val VOICE_GENDERS: Map<String, String> = mapOf(
    "Kore" to "feminine",
    "Aoede" to "feminine",
    "Leda" to "feminine",
    "Autonoe" to "feminine",
    "Despina" to "feminine",
    "Erinome" to "feminine",
    "Laomedeia" to "feminine",
    "Achernar" to "feminine",
    "Pulcherrima" to "feminine",
    "Vindemiatrix" to "feminine",
    "Sulafat" to "feminine",
    "Zephyr" to "feminine",
    "Puck" to "masculine",
    "Charon" to "masculine",
    "Fenrir" to "masculine",
    "Orus" to "masculine",
    "Enceladus" to "masculine",
    "Iapetus" to "masculine",
    "Umbriel" to "masculine",
    "Algieba" to "masculine",
    "Algenib" to "masculine",
    "Rasalgethi" to "masculine",
    "Alnilam" to "masculine",
    "Schedar" to "masculine",
    "Gacrux" to "masculine",
    "Achird" to "masculine",
    "Zubenelgenubi" to "masculine",
    "Sadachbia" to "masculine",
    "Sadaltager" to "masculine",
)

/** The voice a guide of this gender speaks with when nothing more specific is chosen. */
const val FEMININE_VOICE = "Kore"
const val MASCULINE_VOICE = "Charon"

/**
 * The voice [guide] must speak with: the current one when its gender already matches, else the
 * default voice of the guide's gender. A guide with no recorded gender keeps whatever is configured.
 */
fun voiceForGuide(guide: Guide, currentVoice: String): String {
    val want = guide.gender.takeIf { it == "feminine" || it == "masculine" }
        ?: return if (currentVoice in GEMINI_TTS_VOICES) currentVoice else DEFAULT_CLOUD_VOICE
    val current = if (currentVoice in GEMINI_TTS_VOICES) currentVoice else DEFAULT_CLOUD_VOICE
    if (VOICE_GENDERS[current] == want) return current
    return if (want == "feminine") FEMININE_VOICE else MASCULINE_VOICE
}

/** One selectable narration voice. */
data class CloudVoice(
    val name: String,
    val languageCodes: List<String>,
    val gender: String,
) {
    /** The whole name is the label here, e.g. "Kore". */
    val shortName: String get() = name

    val family: String get() = "Gemini 3.8"
}

/**
 * Gemini 3.8 Flash TTS (`gemini-3.8-flash-tts`).
 *
 * This replaces the old Cloud Text-to-Speech call. The model takes the script as a verbatim
 * transcript and the performance as structured metadata:
 *
 *  - `generationConfig.responseModalities` is `["AUDIO"]`.
 *  - `generationConfig.speechConfig.voiceConfig.voice` names a prebuilt voice.
 *  - each text part carries `speech_metadata.style`, a sustained direction for the delivery.
 *  - point-in-time sounds — `<laugh>`, `<sigh>`, `<short pause>`, `<cough>`, `<breath>` — live
 *    inline in the text where they happen.
 *
 * A unary request returns WAV audio (`audio/wav`) with a RIFF header by default, which is what the
 * player is fed. The API key is the Gemini key: this is a Gemini model on the Generative Language
 * API, not the Cloud Text-to-Speech service.
 */
class GeminiTtsClient(
    private val context: Context,
    private val apiKey: () -> String,
) {

    /** Prebuilt voices. The list is static; there is nothing to fetch. */
    fun listVoices(languageCode: String = NARRATION_LANGUAGE): List<CloudVoice> =
        GEMINI_TTS_VOICES.map {
            CloudVoice(name = it, languageCodes = listOf(languageCode), gender = VOICE_GENDERS[it].orEmpty())
        }

    /**
     * The voice [guide] must speak with: the current one when its gender already matches, else the
     * default voice of the guide's gender. A guide with no gender keeps whatever is configured.
     */
    fun voiceForGuide(guide: Guide, currentVoice: String): String {
        val want = guide.gender.takeIf { it == "feminine" || it == "masculine" } ?: return normalizeVoice(currentVoice)
        val current = normalizeVoice(currentVoice)
        if (VOICE_GENDERS[current] == want) return current
        return if (want == "feminine") FEMININE_VOICE else MASCULINE_VOICE
    }

    /**
     * Keeps a voice that Gemini TTS knows, and replaces anything else — such as a Cloud TTS name
     * saved before the switch — with the default, so an old preference cannot break synthesis.
     */
    fun normalizeVoice(name: String): String = if (name in GEMINI_TTS_VOICES) name else DEFAULT_CLOUD_VOICE

    /**
     * Synthesises [text] in [voiceName], with an optional sustained [style], and returns the
     * encoded audio bytes (WAV).
     */
    suspend fun synthesize(
        text: String,
        voiceName: String,
        style: String? = null,
        languageCode: String = NARRATION_LANGUAGE,
    ): ByteArray {
        val key = requireKey()

        val part = JSONObject().apply {
            put("text", text)
            if (!style.isNullOrBlank()) {
                put("speech_metadata", JSONObject().put("style", style))
            }
        }
        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(part))
                    },
                ),
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("voice", voiceName),
                        ),
                    )
                },
            )
        }

        // A long synthesis is occasionally cut off in transport ("connection abort"). Retry once on
        // a transport-level failure; a real refusal is never retried.
        var lastError: AiException? = null
        repeat(2) { attempt ->
            try {
                val json = AiHttp.postJson(
                    "$BASE_URL/models/$MODEL:generateContent?key=$key",
                    body,
                    AppIdentityHeaders.build(context),
                    readTimeoutMs = TTS_TIMEOUT_MS,
                )
                return decodeAudio(json)
            } catch (e: AiException) {
                lastError = e
                if (e.httpCode != 0 || attempt == 1) throw e
                Log.w(TAG, "Gemini TTS transport failure; retrying once", e)
            }
        }
        throw lastError ?: AiException("Gemini TTS failed.")
    }

    private fun decodeAudio(json: JSONObject): ByteArray {
        json.optJSONObject("promptFeedback")?.optString("blockReason")
            ?.takeIf { it.isNotBlank() }
            ?.let { throw AiException("That narration was blocked by Google's safety filters ($it).") }

        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        val encoded = if (parts == null) {
            null
        } else {
            (0 until parts.length())
                .asSequence()
                .mapNotNull { parts.optJSONObject(it)?.optJSONObject("inlineData")?.optString("data") }
                .firstOrNull { it.isNotBlank() }
        }

        if (encoded.isNullOrBlank()) {
            throw AiException("Gemini returned no audio for this voice. Try again or pick another voice.")
        }
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    private fun requireKey(): String {
        val key = apiKey()
        if (key.isBlank()) {
            throw AiException("No Gemini API key is configured.")
        }
        return key
    }

    private companion object {
        const val TAG = "GeminiTtsClient"
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val MODEL = "gemini-3.8-flash-tts"

        /**
         * Synthesising a full stop, a few minutes of audio, measured around 47 s. The default 30 s
         * read timeout would kill it, so speech generation gets a generous ceiling.
         */
        const val TTS_TIMEOUT_MS = 180_000
    }
}
