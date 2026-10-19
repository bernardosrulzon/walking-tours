package com.walkingtours.app.ai

import org.json.JSONObject

/**
 * A guide persona the walker can choose, generated for one tour and remembered for it.
 *
 * [angle] is what this guide dwells on: one aspect of the place, chosen from the tour's own material
 * ("architecture and craft", "religion and ritual"). It is what makes the four offered guides
 * different choices rather than four voices reading the same script, so it is shown on the picker
 * card and used as the guide's licence to expand on a stop. [style] is the guide's temperament: the
 * instruction handed back to the model when it rewrites a stop in this voice, and the note the cloud
 * voice colours its delivery with. Both are stored with the persona rather than re-derived.
 *
 * [gender] is "feminine" or "masculine" when the character reads as one, else blank — the spoken
 * voice is matched to it, so a guide never sounds like the wrong person.
 */
data class Guide(
    val id: String,
    val name: String,
    val tagline: String,
    val style: String,
    val gender: String = "",
    val angle: String = "",
    /** The ElevenLabs voice cast for this guide, chosen once when the persona is created. */
    val elevenVoice: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("tagline", tagline)
        .put("style", style)
        .put("gender", gender)
        .put("angle", angle)
        .put("elevenVoice", elevenVoice)

    companion object {
        fun fromJson(json: JSONObject): Guide? {
            val name = json.optString("name").trim()
            if (name.isBlank()) return null
            return Guide(
                id = json.optString("id").trim().ifBlank { slug(name) },
                name = name,
                tagline = json.optString("tagline").trim(),
                style = json.optString("style").trim(),
                gender = json.optString("gender").trim().lowercase()
                    .takeIf { it == "feminine" || it == "masculine" }.orEmpty(),
                angle = json.optString("angle").trim(),
                elevenVoice = json.optString("elevenVoice").trim(),
            )
        }

        /** A stable-enough id from a display name, for caching and comparison. */
        fun slug(value: String): String = value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "guide" }
    }
}

/**
 * Guides offered when the model cannot be reached — no key, no signal, or a refused request.
 *
 * Deliberately general: each carries one angle and a way of looking, never facts about a place, so
 * they read sensibly on any tour. The model is asked for better, tour-specific ones whenever it can
 * answer.
 */
val FALLBACK_GUIDES: List<Guide> = listOf(
    Guide(
        "storyteller", "Meryem the Storyteller", "Every stone has a story.",
        "warm and anecdotal; opens with a scene and a person, then widens out",
        "feminine",
        angle = "people",
    ),
    Guide(
        "local", "Leyla the Local", "The guidebook always misses lunch.",
        "chatty and practical; food, people and everyday life, present tense",
        "feminine",
        angle = "local life",
    ),
    Guide(
        "architect", "Selin the Architect", "Look up, then look closely.",
        "precise and visual; takes a building apart and puts it back together",
        "feminine",
        angle = "art and architecture",
    ),
    Guide(
        "professor", "Professor Deniz", "Precise, surprising, never dull.",
        "clear and factual; ties the small thing to the big picture without fuss",
        angle = "history",
    ),
)

/**
 * The same personas in Brazilian Portuguese.
 *
 * Only the name, tagline and angle are translated — they are what the walker reads. The style stays
 * in English because it is an instruction to the narrator, not something shown or spoken.
 */
val FALLBACK_GUIDES_PT: List<Guide> = listOf(
    Guide(
        "storyteller", "Meryem, a Contadora de Histórias", "Toda pedra tem uma história.",
        "warm and anecdotal; opens with a scene and a person, then widens out",
        "feminine",
        angle = "pessoas",
    ),
    Guide(
        "local", "Leyla, a Local", "O guia sempre esquece o almoço.",
        "chatty and practical; food, people and everyday life, present tense",
        "feminine",
        angle = "vida cotidiana",
    ),
    Guide(
        "architect", "Selin, a Arquiteta", "Olhe para cima e depois de perto.",
        "precise and visual; takes a building apart and puts it back together",
        "feminine",
        angle = "arte e arquitetura",
    ),
    Guide(
        "professor", "Professor Deniz", "Preciso, surpreendente, nunca monótono.",
        "clear and factual; ties the small thing to the big picture without fuss",
        angle = "história",
    ),
)

/** The built-in guides in the chosen narration language, cast onto ElevenLabs voices. */
fun fallbackGuides(language: String): List<Guide> =
    castElevenVoices(if (language == NARRATION_LANGUAGE_PT_BR) FALLBACK_GUIDES_PT else FALLBACK_GUIDES)
