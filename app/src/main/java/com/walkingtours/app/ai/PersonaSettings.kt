package com.walkingtours.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** How many explorer answers a walker may keep, most important first. */
const val MAX_EXPLORER_PREFERENCES = 3

/** The walker's persona: their ranked explorer preferences, and the guide chosen for each tour. */
data class PersonaState(
    /** Up to [MAX_EXPLORER_PREFERENCES] answers, most important first. */
    val explorers: List<ExplorerType> = emptyList(),
    val guidesByTour: Map<String, Guide> = emptyMap(),
) {
    /** The top preference, or null before the question has been answered. */
    val primaryExplorer: ExplorerType? get() = explorers.firstOrNull()

    fun guide(tourId: String): Guide? = guidesByTour[tourId]
}

/**
 * Persists the persona: the ranked explorer answers (asked once) and the chosen guide per tour.
 *
 * Kept small and dependency-free like [AiSettings], and separate from it because this is content —
 * who is telling the story — rather than configuration. Nothing here touches the database.
 */
class PersonaSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("persona_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PersonaState> = _state.asStateFlow()

    val current: PersonaState get() = _state.value

    fun setExplorers(explorers: List<ExplorerType>) {
        val capped = explorers.distinct().take(MAX_EXPLORER_PREFERENCES)
        prefs.edit().putString(KEY_EXPLORERS, capped.joinToString(",") { it.id }).apply()
        _state.value = _state.value.copy(explorers = capped)
    }

    fun setGuide(tourId: String, guide: Guide) {
        val guides = _state.value.guidesByTour + (tourId to guide)
        prefs.edit().putString(KEY_GUIDES, encode(guides)).apply()
        _state.value = _state.value.copy(guidesByTour = guides)
    }

    fun clearGuide(tourId: String) {
        val guides = _state.value.guidesByTour - tourId
        prefs.edit().putString(KEY_GUIDES, encode(guides)).apply()
        _state.value = _state.value.copy(guidesByTour = guides)
    }

    private fun load(): PersonaState {
        val raw = prefs.getString(KEY_EXPLORERS, null)
        val explorers = if (!raw.isNullOrBlank()) {
            raw.split(",")
                .mapNotNull { ExplorerType.fromId(it.trim()) }
                .distinct()
                .take(MAX_EXPLORER_PREFERENCES)
        } else {
            // Before ranking existed there was a single answer; carry it over rather than re-ask.
            ExplorerType.fromId(prefs.getString(KEY_LEGACY_EXPLORER, null))
                ?.let { listOf(it) }
                .orEmpty()
        }
        return PersonaState(
            explorers = explorers,
            guidesByTour = decode(prefs.getString(KEY_GUIDES, null)),
        )
    }

    private fun encode(guides: Map<String, Guide>): String {
        val root = JSONObject()
        guides.forEach { (tourId, guide) -> root.put(tourId, guide.toJson()) }
        return root.toString()
    }

    private fun decode(raw: String?): Map<String, Guide> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { tourId ->
                    root.optJSONObject(tourId)?.let { json ->
                        Guide.fromJson(json)?.let { guide -> put(tourId, guide) }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val KEY_EXPLORERS = "explorer_types"
        const val KEY_LEGACY_EXPLORER = "explorer_type"
        const val KEY_GUIDES = "guides_by_tour"
    }
}
