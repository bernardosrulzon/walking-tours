package com.walkingtours.app.audio

import com.walkingtours.app.ai.AiSettings
import com.walkingtours.app.ai.ELEVENLABS_MODEL
import com.walkingtours.app.ai.ElevenLabsClient
import com.walkingtours.app.ai.Guide
import com.walkingtours.app.ai.NEUTRAL_DETOUR_GUIDE
import com.walkingtours.app.ai.elevenVoiceFor
import java.io.ByteArrayOutputStream

/**
 * ElevenLabs v4, cast onto the guide's own voice.
 *
 * The character comes from three places, in this order: the voice the guide was cast onto, the
 * delivery tag at the top of the text, and the words themselves. The app never lets the model invent
 * the character at request time — that is what turns a guide into an impression — it only hands over
 * a voice chosen for this persona and a restrained direction.
 */
internal class ElevenLabsCloudVoice(
    private val settings: AiSettings,
    private val client: ElevenLabsClient,
) : CloudVoice {

    override val id = "elevenlabs"
    override val extension = "mp3"
    override val isAvailable: Boolean get() = settings.current.hasElevenLabsKey

    override val label: String get() = "ElevenLabs \u00b7 $ELEVENLABS_MODEL"

    override val unavailableMessage: String
        get() = "Add an ElevenLabs API key to use this voice, or switch back to Gemini in Settings."

    override fun cacheKey(text: String, style: String?, guide: Guide?): String {
        val voice = elevenVoiceFor(guide ?: NEUTRAL_DETOUR_GUIDE)
        return "$id|$ELEVENLABS_MODEL|${voice.id}|${settings.current.narrationLanguage}|" +
            "$AUDIO_CACHE_VERSION|${ElevenLabsText.prepare(text, style)}"
    }

    override suspend fun synthesize(
        text: String,
        style: String?,
        language: String,
        guide: Guide?,
    ): ByteArray {
        val voice = elevenVoiceFor(guide ?: NEUTRAL_DETOUR_GUIDE)
        val prepared = ElevenLabsText.prepare(text, style)
        val chunks = ElevenLabsText.split(prepared)
        val seed = ElevenLabsText.seed(prepared)

        // A stop normally fits in one request. When it does not, the chunks are stitched, and each
        // request gets its neighbours as previous_text/next_text so the voice keeps its pitch and
        // pace across the join instead of taking a new breath.
        if (chunks.size == 1) return client.synthesize(prepared, voice.id, language, seed = seed)
        val audios = chunks.mapIndexed { index, chunk ->
            client.synthesize(
                text = chunk,
                voiceId = voice.id,
                language = language,
                previousText = chunks.getOrNull(index - 1),
                nextText = chunks.getOrNull(index + 1),
                seed = seed,
            )
        }
        return concatMp3(audios)
    }
}

/**
 * Text hygiene for ElevenLabs, which speaks in square-bracket audio tags rather than Gemini's angle
 * brackets.
 *
 * `<laugh>` becomes `[laughs]`, `<sigh>` `[sighs]`, `<breath>` `[exhales]`, `<cough>` `[coughs]`,
 * `<short pause>` `[short pause]` — the tags v4 actually follows. Anything else the rewrite
 * invented is dropped rather than read aloud. A restrained delivery tag is prepended from the
 * stop's style direction, so the voice acts the guide's mood for this stop without the app ever
 * asking it to do an impression.
 */
internal object ElevenLabsText {

    private val tagPattern = Regex("<[^>]*>")

    private val spokenTags = mapOf(
        "laugh" to "laughs",
        "sigh" to "sighs",
        "breath" to "exhales",
        "cough" to "coughs",
        "short pause" to "short pause",
    )

    fun prepare(text: String, style: String?): String {
        val withTags = tagPattern.replace(text) { match ->
            val inner = match.value.substring(1, match.value.length - 1).trim().lowercase()
            spokenTags[inner]?.let { "[$it]" } ?: " "
        }
        val tidy = withTags
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("(?:[ \\t]*\\n){3,}"), "\n\n")
            .trim()
        val delivery = deliveryTag(style) ?: return tidy
        return "$delivery $tidy"
    }

    /**
     * The per-stop delivery direction, as the one audio tag v4 understands.
     *
     * It is capped and always framed with "warm, natural, understated", so a verbose style sentence
     * cannot turn into a performance instruction of its own.
     */
    private fun deliveryTag(style: String?): String? {
        val clean = style
            ?.replace(Regex("[\\[\\]\\r\\n]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(120)
            ?.trimEnd(',', ';', ' ')
            .orEmpty()
        return if (clean.isBlank()) {
            "[warm, natural, understated]"
        } else {
            "[warm, natural, understated; $clean]"
        }
    }

    /**
     * Splits a narration that is longer than one ElevenLabs request.
     *
     * ElevenLabs documents a 10,000-character limit for `eleven_v4` (about ten minutes of audio).
     * A stop here is 2,700–3,500 characters, so this never fires in practice; it exists so an
     * unusually long text can never become an invalid request, not to keep requests small. The
     * chunks are stitched and carry previous/next text, but splitting is still a last resort: each
     * request is its own performance.
     */
    fun split(text: String, maxChars: Int = MAX_CHARS): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) chunks.add(current.toString().trim())
            current.clear()
        }

        fun appendParagraph(paragraph: String) {
            val joiner = if (current.isEmpty()) "" else "\n\n"
            if (current.length + joiner.length + paragraph.length <= maxChars) {
                current.append(joiner).append(paragraph)
                return
            }
            if (paragraph.length <= maxChars) {
                flush()
                current.append(paragraph)
                return
            }
            // A paragraph of its own is too long: fall back to sentence-sized pieces.
            flush()
            for (sentence in paragraph.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }) {
                if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChars) flush()
                if (sentence.length <= maxChars) {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                } else {
                    flush()
                    chunks.addAll(sentence.chunked(maxChars).map { it.trim() }.filter { it.isNotEmpty() })
                }
            }
            flush()
        }

        text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach(::appendParagraph)
        flush()
        return chunks.ifEmpty { listOf(text) }
    }

    /** A stable seed per narration, so a repeated request samples the same way. */
    fun seed(text: String): Long = Math.floorMod(text.hashCode(), Int.MAX_VALUE).toLong()

    private const val MAX_CHARS = 10_000
}

/**
 * Joins per-chunk MP3s into one stream.
 *
 * MP3 frames are self-contained, so concatenating them plays as one file. Only the leading ID3
 * metadata of later chunks has to go: an ID3 tag in the middle of the stream is not something every
 * decoder expects, and the first chunk already carries the file's metadata.
 */
private fun concatMp3(chunks: List<ByteArray>): ByteArray {
    if (chunks.size == 1) return chunks[0]
    val out = ByteArrayOutputStream()
    chunks.forEachIndexed { index, bytes ->
        val start = if (index == 0) 0 else id3v2Length(bytes)
        var end = bytes.size
        if (index < chunks.lastIndex && end - start > 128) {
            val tag = bytes.size - 128
            if (bytes[tag] == 'T'.code.toByte() &&
                bytes[tag + 1] == 'A'.code.toByte() &&
                bytes[tag + 2] == 'G'.code.toByte()
            ) {
                end = tag
            }
        }
        out.write(bytes, start, end - start)
    }
    return out.toByteArray()
}

/** Length of a leading ID3v2 tag, or 0 when the chunk has none. */
private fun id3v2Length(bytes: ByteArray): Int {
    if (bytes.size < 10 ||
        bytes[0] != 'I'.code.toByte() ||
        bytes[1] != 'D'.code.toByte() ||
        bytes[2] != '3'.code.toByte()
    ) {
        return 0
    }
    val size = ((bytes[6].toInt() and 0x7F) shl 21) or
        ((bytes[7].toInt() and 0x7F) shl 14) or
        ((bytes[8].toInt() and 0x7F) shl 7) or
        (bytes[9].toInt() and 0x7F)
    val footer = if (bytes[5].toInt() and 0x10 != 0) 10 else 0
    return (10 + size + footer).coerceAtMost(bytes.size)
}
