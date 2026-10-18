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
}
