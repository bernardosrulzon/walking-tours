package com.walkingtours.app.ai

import org.json.JSONObject

/**
 * A guide persona the walker can choose, generated for one tour and remembered for it.
 *
 * [style] is the instruction handed back to the model when it rewrites a stop in this voice, so it
 * is stored with the persona rather than re-derived.
 */
data class Guide(
    val id: String,
    val name: String,
    val tagline: String,
    val style: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("tagline", tagline)
        .put("style", style)

    companion object {
        fun fromJson(json: JSONObject): Guide? {
            val name = json.optString("name").trim()
            if (name.isBlank()) return null
            return Guide(
                id = json.optString("id").trim().ifBlank { slug(name) },
                name = name,
                tagline = json.optString("tagline").trim(),
                style = json.optString("style").trim(),
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
 * Deliberately general: they carry a way of looking, not facts about a place, so they read sensibly
 * on any tour. The model is asked for better, tour-specific ones whenever it can answer.
 */
val FALLBACK_GUIDES: List<Guide> = listOf(
    Guide(
        "storyteller", "Meryem the Storyteller", "Every stone has a story.",
        "warm and anecdotal; opens with a scene and a person, then widens out",
    ),
    Guide(
        "detective", "Kemal the Detective", "Ask who really benefited.",
        "dry and curious; follows money, motives and the version nobody printed",
    ),
    Guide(
        "local", "Leyla the Local", "The guidebook always misses lunch.",
        "chatty and practical; food, people and everyday life, present tense",
    ),
    Guide(
        "professor", "Professor Deniz", "Precise, surprising, never dull.",
        "clear and factual; ties the small thing to the big picture without fuss",
    ),
    Guide(
        "poet", "Emre the Poet", "Slow down. Look again.",
        "lyrical and sensory; finds the beauty in worn stone and small moments",
    ),
)
