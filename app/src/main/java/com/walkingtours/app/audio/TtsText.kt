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
     * What is sent to Gemini TTS: the documented tags kept verbatim (normalised to their
     * canonical lowercase form), every other `<…>` tag dropped. An invented tag such as
     * `<pause>` would otherwise be read aloud or break the delivery.
     */
    fun sanitizeForCloud(text: String): String {
        val kept = tagPattern.replace(text) { match ->
            val inner = match.value.substring(1, match.value.length - 1).trim().lowercase()
            if (inner in cloudTags) "<$inner>" else " "
        }
        return collapseWhitespace(kept)
    }

    /**
     * Split a narration into paragraph-sized syntheses, each no longer than [maxChars].
     *
     * A whole stop runs 400–450 words (~3 minutes), and a single request that long drifts: the
     * voice changes a couple of paragraphs in, and blank lines read as speaker turns. Short
     * requests with the same voice and style stay stable, so one bad paragraph can at worst fail
     * its own synthesis — it can never change the voice of the rest. Small paragraphs are
     * merged so a stop of short lines does not become dozens of requests.
     */
    fun splitForCloud(text: String, maxChars: Int = MAX_CLOUD_CHUNK_CHARS): List<String> {
        val clean = sanitizeForCloud(text)
        if (clean.length <= maxChars) return listOf(clean)
        val paragraphs = clean.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(clean)
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) chunks.add(current.toString().trim())
            current.clear()
        }
        fun appendPiece(piece: String) {
            if (current.isEmpty()) {
                current.append(piece)
                return
            }
            if (current.length + 2 + piece.length <= maxChars) {
                current.append("\n\n").append(piece)
            } else {
                flush()
                current.append(piece)
            }
        }
        for (paragraph in paragraphs) {
            if (paragraph.length <= maxChars) {
                appendPiece(paragraph)
                continue
            }
            // One over-long paragraph: split on sentence boundaries so no request is huge.
            flush()
            val sentences = paragraph.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
            for (sentence in sentences) {
                if (sentence.length <= maxChars) {
                    appendPiece(sentence)
                } else {
                    // Pathological single sentence: hard-split so synthesis stays bounded.
                    flush()
                    chunks.addAll(sentence.chunked(maxChars).map { it.trim() }.filter { it.isNotEmpty() })
                }
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

    const val MAX_CLOUD_CHUNK_CHARS = 1000
}
