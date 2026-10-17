package com.walkingtours.app.audio

/**
 * Text hygiene at the audio seam.
 *
 * The guide's rewrite carries inline vocal tags for the cloud voice (`<laugh>`, `<short pause>`,
 * …). Both engines receive the same tagged text, and each must turn it into something it can
 * speak — the phone's engine cannot perform tags, and the cloud model only knows a documented
 * handful. Centralised here so the transcript, the cloud voice and the device voice agree.
 */
internal object TtsText {

    /** The only inline tags the Gemini TTS request supports; everything else is model invention. */
    val cloudTags: Set<String> = setOf("laugh", "sigh", "breath", "cough", "short pause")

    private val tagPattern = Regex("<[^>]*>")

    /**
     * What is sent to Gemini TTS: the documented performance tags kept verbatim (normalised to
     * their canonical lowercase form), every other `<…>` tag dropped, and paragraph breaks
     * flattened into single spaces.
     *
     * The tags are the performance the walker should hear, so they survive. Everything else does
     * not: an invented tag such as `<pause>` would be read aloud, and a blank line between
     * paragraphs reads as a speaker turn — the model answers it with what sounds like a different
     * narrator a couple of paragraphs in. Without the blank lines the stop is one continuous
     * transcript, so one request comes back in one voice from start to finish.
     */
    fun sanitizeForCloud(text: String): String {
        val kept = tagPattern.replace(text) { match ->
            val inner = match.value.substring(1, match.value.length - 1).trim().lowercase()
            if (inner in cloudTags) "<$inner>" else " "
        }
        return kept.replace(WHITESPACE, " ").trim()
    }

    /**
     * Split a narration into the requests sent to the cloud voice, each no longer than [maxChars].
     *
     * The whole stop goes in ONE request whenever it fits, because separate requests are separate
     * performances: each one comes back with its own take on the same voice, and the joins between
     * them are exactly where listeners hear the narrator change. [maxChars] is therefore well above
     * a stop (a five-minute introduction is ~3,500 characters) and only exists so a pathologically
     * long text stays inside the request timeout. Chunks carry no paragraph breaks, so a split can
     * never be mistaken for a change of speaker.
     */
    fun splitForCloud(text: String, maxChars: Int = MAX_CLOUD_CHUNK_CHARS): List<String> {
        val clean = sanitizeForCloud(text)
        if (clean.length <= maxChars) return listOf(clean)

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) chunks.add(current.toString().trim())
            current.clear()
        }
        for (sentence in clean.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }) {
            if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChars) flush()
            if (sentence.length <= maxChars) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            } else {
                // Pathological single sentence: hard-split so synthesis stays bounded.
                flush()
                chunks.addAll(sentence.chunked(maxChars).map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        flush()
        return chunks.ifEmpty { listOf(clean) }
    }

    /**
     * What the on-device engine speaks: no angle-bracket tag ever reaches the speaker.
     *
     * `<short pause>` becomes a full stop — the only pause every TTS engine honours — and the
     * remaining performance tags (`<laugh>`, `<sigh>`, …) are dropped: the phone's voice cannot
     * laugh, and reading the word "laugh" aloud is worse than skipping it. Paragraph breaks
     * survive, so the spoken rhythm still follows the transcript.
     */
    fun spokenForDevice(text: String): String {
        val withPauses = text.replace(Regex("(?i)<\\s*short\\s+pause\\s*>"), ". ")
        return collapseWhitespace(tagPattern.replace(withPauses, " "))
    }

    private fun collapseWhitespace(text: String): String =
        text.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("(?:[ \\t]*\\n){2,}"), "\n\n")
            .trim()

    private val WHITESPACE = Regex("\\s+")

    /**
     * Roughly twice the longest narration the guide ever writes, so a stop (and its introduction)
     * is always synthesised in a single request. Latency scales with the text: a 2,700-character
     * introduction takes about 40 s, well inside the client's timeout.
     */
    const val MAX_CLOUD_CHUNK_CHARS = 6000
}
