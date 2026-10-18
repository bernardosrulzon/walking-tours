package com.walkingtours.app.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** The walker's persona: the guide chosen for each tour, and how they asked it to tell it. */
data class PersonaState(
    val guidesByTour: Map<String, Guide> = emptyMap(),
    /** Per-tour quick feedback on the telling, folded into every rewrite for that tour. */
    val toneByTour: Map<String, String> = emptyMap(),
) {
    fun guide(tourId: String): Guide? = guidesByTour[tourId]

    fun tone(tourId: String): String? = toneByTour[tourId]?.takeIf { it.isNotBlank() }
}

/**
 * Persists the persona: the chosen guide per tour, and any per-tour feedback on how the guide should
 * tell it.
 *
 * Kept small and dependency-free like [AiSettings], and separate from it because this is content —
 * who is telling the story, and how — rather than configuration. Nothing here touches the database.
 */
class PersonaSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("persona_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PersonaState> = _state.asStateFlow()

    val current: PersonaState get() = _state.value

    fun setGuide(tourId: String, guide: Guide) {
        val guides = _state.value.guidesByTour + (tourId to guide)
        prefs.edit().putString(KEY_GUIDES, encodeGuides(guides)).apply()
        _state.value = _state.value.copy(guidesByTour = guides)
    }

    fun clearGuide(tourId: String) {
        val guides = _state.value.guidesByTour - tourId
        prefs.edit().putString(KEY_GUIDES, encodeGuides(guides)).apply()
        _state.value = _state.value.copy(guidesByTour = guides)
    }

    fun setTone(tourId: String, tone: String) {
        val tones = _state.value.toneByTour.toMutableMap()
        val trimmed = tone.trim()
        if (trimmed.isBlank()) tones.remove(tourId) else tones[tourId] = trimmed
        val root = JSONObject()
        tones.forEach { (id, note) -> root.put(id, note) }
        prefs.edit().putString(KEY_TONES, root.toString()).apply()
        _state.value = _state.value.copy(toneByTour = tones)
    }

    private fun load(): PersonaState = PersonaState(
        guidesByTour = decodeGuides(prefs.getString(KEY_GUIDES, null)),
        toneByTour = decodeTones(prefs.getString(KEY_TONES, null)),
    )

    private fun encodeGuides(guides: Map<String, Guide>): String {
        val root = JSONObject()
        guides.forEach { (tourId, guide) -> root.put(tourId, guide.toJson()) }
        return root.toString()
    }

    private fun decodeGuides(raw: String?): Map<String, Guide> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { tourId ->
                    root.optJSONObject(tourId)?.let { json ->
                        // A persona saved before guides carried an angle is no longer a valid choice —
                        // the four cards would read alike and the prompt would have nothing to dwell
                        // on — so it is dropped and the walker picks again.
                        Guide.fromJson(json)
                            ?.takeIf { it.angle.isNotBlank() }
                            ?.let { guide -> put(tourId, guide) }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun decodeTones(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { tourId ->
                    root.optString(tourId).takeIf { it.isNotBlank() }?.let { put(tourId, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val KEY_GUIDES = "guides_by_tour"
        const val KEY_TONES = "tones_by_tour"
    }
}
