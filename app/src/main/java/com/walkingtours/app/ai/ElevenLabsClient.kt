package com.walkingtours.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The ElevenLabs model used for narration: v4, the only generation that follows audio tags well. */
const val ELEVENLABS_MODEL = "eleven_v4"

/**
 * One voice from the curated ElevenLabs palette.
 *
 * [character] is what the voice sounds like, in plain words. It exists to make the mapping from a
 * guide to a voice deliberate rather than incidental: this is the closest thing the app has to
 * casting the guide, and it is the main way the character comes through, because ElevenLabs has no
 * instruction field — the voice, the delivery tag and the words do the acting.
 */
data class ElevenVoice(
    val id: String,
    val name: String,
    val gender: String,
    val character: String,
)

/**
 * The voices the app casts guides from.
 *
 * All of them are premade voices that call the API without a paid plan's library access (some
 * ElevenLabs voices answer 402 on a free account, and the key may also lack `voices_read`, so the
 * app does not discover voices at runtime). They are warm, clear, narration-friendly voices: the
 * point is a guide a walker would happily listen to for fifteen minutes, not an impressionist.
 */
val ELEVEN_TTS_VOICES: List<ElevenVoice> = listOf(
    ElevenVoice("JBFqnCBsd6RMkjVDRZzb", "George", "masculine", "warm, wry British storyteller"),
    ElevenVoice("pNInz6obpgDQGcFmaJgB", "Adam", "masculine", "deep, calm, unhurried"),
    ElevenVoice("onwK4e9ZLuTAKqWW03F9", "Daniel", "masculine", "crisp, dry newsreader"),
    ElevenVoice("nPczCjzI2devNBz1zQrb", "Brian", "masculine", "resonant and easygoing"),
    ElevenVoice("EXAVITQu4vr4xnSDxMaL", "Bella", "feminine", "soft, warm, expressive"),
    ElevenVoice("Xb7hH8MSUJpSbSDYk0k2", "Alice", "feminine", "clear, friendly and bright"),
    ElevenVoice("pFZP5JQG7iQjIQuC4Bku", "Lily", "feminine", "lively and conversational"),
    ElevenVoice("XrExE9yKIg1WjnnlVkGX", "Matilda", "feminine", "characterful and wry"),
)

/** The voices that could read a guide of [gender]; a blank gender may be cast from either. */
fun elevenVoicesFor(gender: String): List<ElevenVoice> {
    val matching = ELEVEN_TTS_VOICES.filter { gender.isBlank() || it.gender == gender }
    return matching.ifEmpty { ELEVEN_TTS_VOICES }
}

/**
 * The voice [guide] speaks with on ElevenLabs.
 *
 * A guide generated since the switch carries its voice. Older personas, and the neutral detour
 * voice, fall back to a stable pick from the gender's bucket, so the same guide keeps the same voice
 * across sessions instead of changing on every play.
 */
fun elevenVoiceFor(guide: Guide): ElevenVoice {
    ELEVEN_TTS_VOICES.firstOrNull { it.id == guide.elevenVoice }?.let { return it }
    val candidates = elevenVoicesFor(guide.gender)
    val index = Math.floorMod(guide.id.hashCode(), candidates.size)
    return candidates[index]
}

/**
 * Casts a freshly generated set of guides, giving each a distinct voice within its gender.
 *
 * Casting happens once, when the four guides are created, rather than being derived at playback:
 * two guides of the same gender would otherwise be free to hash onto the same voice, and the whole
 * point of four guides is that they sound like four different people. The choice is persisted with
 * the persona, so it is stable from then on.
 */
fun castElevenVoices(guides: List<Guide>): List<Guide> {
    val used = mutableSetOf<String>()
    return guides.map { guide ->
        val candidates = elevenVoicesFor(guide.gender)
        val pick = candidates.firstOrNull { it.id !in used }
            ?: candidates[Math.floorMod(guide.id.hashCode(), candidates.size)]
        used += pick.id
        guide.copy(elevenVoice = pick.id)
    }
}

/**
 * ElevenLabs text-to-speech (`/v1/text-to-speech/{voice_id}`).
 *
 * The request carries the two things the model understands beyond the words:
 *
 *  - **Audio tags**, inline in the text, in square brackets (`[laughs]`, `[sighs]`, `[short pause]`).
 *    v4 follows them well; older models would read them aloud, which is why the app only ever calls
 *    v4.
 *  - **Voice settings**: v4 exposes stability and similarity, not the style and speed sliders of
 *    earlier models. Stability is what keeps a guide from over-acting, so it sits above the default:
 *    the same voice, giving the same performance, rather than a fresh dramatic take each time.
 *
 * `previous_text`/`next_text` and a stable `seed` keep a narration that has to be split into more
 * than one request sounding continuous.
 */
class ElevenLabsClient(
    private val apiKey: () -> String,
) {

    /**
     * Synthesises [text] in [voiceId] and returns MP3 bytes.
     *
     * MP3 rather than WAV/PCM on purpose: ElevenLabs only offers those formats on a paid plan, and
     * MediaPlayer plays MP3 just as well. [previousText] and [nextText] are the neighbouring slices
     * when a long narration is split, so the voice keeps its pitch and pace across the join.
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        language: String = NARRATION_LANGUAGE,
        previousText: String? = null,
        nextText: String? = null,
        seed: Long? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val key = apiKey().trim()
        if (key.isBlank()) throw AiException("No ElevenLabs API key is configured.")

        val body = JSONObject().apply {
            put("text", text)
            put("model_id", ELEVENLABS_MODEL)
            put(
                "voice_settings",
                JSONObject().apply {
                    // v4 has two knobs. Stability above the default keeps the same guide sounding
                    // like the same performance instead of a new dramatic take on every stop;
                    // similarity keeps the output close to the cast voice. Style and speed do not
                    // exist on v4, so sending them would only be decoration.
                    put("stability", 0.62)
                    put("similarity_boost", 0.8)
                },
            )
            // Numbers, dates and currencies are everywhere in a history tour; normalize them so
            // "1453" and "$3" are spoken the way a person would.
            put("apply_text_normalization", "on")
            if (language == NARRATION_LANGUAGE_PT_BR) put("language_code", "pt")
            seed?.let { put("seed", it) }
            previousText?.takeIf { it.isNotBlank() }?.let { put("previous_text", it.takeLast(1500)) }
            nextText?.takeIf { it.isNotBlank() }?.let { put("next_text", it.take(1500)) }
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BASE_URL/text-to-speech/$voiceId?output_format=$OUTPUT_FORMAT").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                // A stop is minutes of audio; ElevenLabs is fast, but not chat-fast.
                readTimeout = 180_000
                setRequestProperty("xi-api-key", key)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "audio/mpeg")
                doOutput = true
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                throw AiException(friendlyMessage(code, detail), code)
            }
            connection.inputStream.use { it.readBytes() }
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException("Could not reach ElevenLabs. Check your internet connection.", 0, e)
        } finally {
            connection?.disconnect()
        }
    }

    /** Turns an ElevenLabs failure into something a walker can act on. */
    private fun friendlyMessage(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("detail")
        }.getOrNull()
        val message = detail?.optString("message").orEmpty().trim()
        val reason = detail?.optString("code").orEmpty()

        val base = when {
            code == 401 -> "ElevenLabs rejected the API key. Check that it is valid and allowed to " +
                "generate speech."
            code == 402 || reason == "paid_plan_required" || reason == "quota_exceeded" ->
                "ElevenLabs needs credits or a paid plan for this voice. Add credits, or switch " +
                    "back to the Gemini voice in Settings."
            code == 422 -> "ElevenLabs rejected this narration."
            code == 429 -> "ElevenLabs is rate-limiting requests. Wait a moment and try again."
            code in 500..599 -> "ElevenLabs is temporarily unavailable. Try again."
            else -> "ElevenLabs request failed with HTTP $code."
        }
        return if (message.isBlank()) base else "$base\n\n$message"
    }

    private companion object {
        const val BASE_URL = "https://api.elevenlabs.io/v1"
        const val OUTPUT_FORMAT = "mp3_44100_128"
    }
}
