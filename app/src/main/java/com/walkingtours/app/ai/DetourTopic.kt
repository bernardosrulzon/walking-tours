package com.walkingtours.app.ai

import org.json.JSONObject

/**
 * The voice a detour is told in when the walker has not chosen a guide. Deliberately
 * unremarkable: it belongs to no persona, changes nothing about the tour's guide choice, and
 * keeps the configured TTS voice (its gender is blank, so no voice switch fires).
 */
val NEUTRAL_DETOUR_GUIDE = Guide(
    id = "neutral",
    name = "A knowledgeable local",
    tagline = "",
    style = "warm, clear and unhurried; explains context like a good museum audioguide",
)

/**
 * One deep-dive subject offered as a detour from a tour: country-level context — history,
 * geopolitics, culture, religion, economy — worth a few minutes of listening.
 *
 * Topics are generated per tour and cached in memory; unlike stops they have no authored text, so
 * there is nothing to fall back to when generation fails.
 */
data class DetourTopic(
    val id: String,
    val title: String,
    val blurb: String,
    /** Short search query used to find a relevant photo for this topic. */
    val imageQuery: String,
) {
    companion object {
        /** Blurbs are one short sentence on a card: first sentence only, hard-capped. */
        const val MAX_BLURB_CHARS = 140

        fun fromJson(json: JSONObject): DetourTopic? {
            val title = json.optString("title").trim()
            if (title.isBlank()) return null
            return DetourTopic(
                id = json.optString("id").trim().ifBlank { Guide.slug(title) },
                title = title,
                blurb = shortSentence(json.optString("blurb")),
                imageQuery = json.optString("imageQuery").trim().ifBlank { title },
            )
        }

        /**
         * First sentence only, cut at a word boundary with an ellipsis when over the cap — so a
         * chatty model can never stretch the card, whatever the prompt asked for.
         */
        private fun shortSentence(raw: String): String {
            val first = raw.trim().split(Regex("[.!?…]+\\s+")).firstOrNull()?.trim().orEmpty()
            if (first.length <= MAX_BLURB_CHARS) return first
            val cut = first.take(MAX_BLURB_CHARS).trimEnd()
            return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length).trimEnd() + "…"
        }
    }
}
