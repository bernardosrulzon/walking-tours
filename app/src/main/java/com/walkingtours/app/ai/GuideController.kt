package com.walkingtours.app.ai

import android.content.Context
import android.util.Log
import com.walkingtours.app.data.TourRepository
import com.walkingtours.app.data.db.StopEntity
import com.walkingtours.app.data.db.TourEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A personalised narration, tagged with the signature it was generated under. */
data class GuideNarration(val signature: String, val text: String, val style: String?)

/** What is handed to the narration engine: the words, and the delivery to perform them with. */
data class SpokenLine(val text: String, val style: String?)

/**
 * What one rewrite is about.
 *
 * [key] is the cache id — a stop's id, or the introduction's pseudo-id. [context] is the one line
 * the model is given about what it is rewriting ("Stop 3: German Fountain (Monument)"). [authored]
 * is the text it rewrites, and the fallback if it cannot.
 */
data class NarrationRequest(
    val key: String,
    val context: String,
    val authored: String,
    /** True for the walk's introduction, where the guide should introduce themselves. */
    val isIntroduction: Boolean = false,
)

/**
 * The signature a narration is generated under.
 *
 * Everything that should invalidate a cached narration is in here: which guide is telling it, how
 * that guide speaks, what the walker asked to hear, and any feedback they left for this tour. Change
 * any of it and the old entries stop matching, so they regenerate; change none of it and the text
 * and its audio are reused.
 */
/** Bump when the rewrite prompts change, so cached narrations from the old prompts are ignored. */
const val NARRATION_PROMPT_VERSION = "3"

fun guideSignature(guide: Guide?, explorers: List<ExplorerType>, tone: String?): String? =
    guide?.let {
        listOf(
            NARRATION_PROMPT_VERSION,
            it.id,
            it.style,
            explorers.joinToString(",") { explorer -> explorer.id },
            tone.orEmpty(),
        ).joinToString("|")
    }

/**
 * The guide personas, and the narration they tell.
 *
 * Two jobs. It proposes five guides for a tour from the walker's ranked preferences and where the
 * tour is, and — once a guide is chosen — it rewrites one narration at a time, on demand.
 *
 * Each rewrite returns both the words and a delivery [SpokenLine.style]. The words may carry inline
 * vocal tags (`<laugh>`, `<sigh>`, `<short pause>`) where a moment calls for one; the engine performs
 * them and the screen strips them, so the walker hears the performance and reads clean prose.
 *
 * Generation is lazy: a stop is only rewritten when it is about to be played, so a walk that stops
 * early never pays for stops it did not reach. Results are cached in memory and on disk, keyed by
 * the [guideSignature], so they survive the process and are not regenerated while the guide is
 * unchanged.
 */
class GuideController(
    context: Context,
    private val repository: TourRepository,
    private val geminiClient: GeminiClient,
    private val settings: AiSettings,
    private val personaSettings: PersonaSettings,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = NarrationStore(context.applicationContext)

    /** Persisted narrations, keyed by "tourId|narrationKey". */
    private val _narrations = MutableStateFlow(store.load())
    val narrations: StateFlow<Map<String, GuideNarration>> = _narrations.asStateFlow()

    private val _loading = MutableStateFlow<Set<String>>(emptySet())

    /** Narration keys whose rewrite is in flight, so the UI can show a loading state for that stop. */
    val loading: StateFlow<Set<String>> = _loading.asStateFlow()

    /** In-flight rewrites, so two callers asking for the same stop share one model call. */
    private val jobs = mutableMapOf<String, Deferred<SpokenLine>>()

    private var model: String? = null

    /** The signature the current guide, preferences and tone produce for [tourId], or null. */
    fun signatureFor(tourId: String): String? {
        val persona = personaSettings.current
        return guideSignature(persona.guide(tourId), persona.explorers, persona.tone(tourId))
    }

    private fun cacheKey(tourId: String, key: String) = "$tourId|$key"

    /** True when [key] already has a narration generated under the current signature. */
    fun hasNarration(tourId: String, key: String): Boolean {
        val signature = signatureFor(tourId) ?: return false
        return _narrations.value[cacheKey(tourId, key)]?.signature == signature
    }

    /**
     * The guide's version of [key] if it has been generated for the current signature, else the
     * authored text. Synchronous and safe to call from composition.
     */
    fun effectiveNarration(tourId: String, key: String, authored: String): String {
        val signature = signatureFor(tourId) ?: return authored
        val cached = _narrations.value[cacheKey(tourId, key)] ?: return authored
        return if (cached.signature == signature) cached.text else authored
    }

    /** Forget one generated narration, e.g. to force a re-roll after feedback. */
    fun invalidate(tourId: String, key: String) {
        val id = cacheKey(tourId, key)
        if (_narrations.value.containsKey(id)) {
            _narrations.value = _narrations.value - id
            persist()
        }
    }

    /**
     * Five guide options for [tourId] and these ranked preferences, or the built-in fallbacks when
     * the model cannot be reached. Never throws.
     */
    suspend fun suggestGuides(tourId: String, explorers: List<ExplorerType>): List<Guide> {
        if (!settings.current.hasGeminiKey) return FALLBACK_GUIDES
        val tour = runCatching { repository.getTour(tourId) }.getOrNull() ?: return FALLBACK_GUIDES
        val stops = runCatching { repository.getStops(tourId) }.getOrDefault(emptyList())
        return runCatching { requestGuides(tour, stops, explorers) }
            .onFailure { Log.w(TAG, "Guide suggestion failed; using fallback guides", it) }
            .getOrNull()
            ?.takeIf { it.size >= 3 }
            ?: FALLBACK_GUIDES
    }

    /**
     * The narration to play for one stop — generated now if it has not been already. Suspends until
     * the text is ready, so the caller can hold the "loading" state for exactly that stop. Falls
     * back to [NarrationRequest.authored] on any failure.
     */
    suspend fun narration(tourId: String, request: NarrationRequest): SpokenLine {
        val persona = personaSettings.current
        val guide = persona.guide(tourId)
            ?: return SpokenLine(request.authored, null).also {
                Log.i(TAG, "No guide for $tourId; authored text for ${request.key}")
            }
        val signature = guideSignature(guide, persona.explorers, persona.tone(tourId))
            ?: return SpokenLine(request.authored, null)
        val key = cacheKey(tourId, request.key)
        _narrations.value[key]
            ?.takeIf { it.signature == signature }
            ?.let {
                Log.i(TAG, "Cache hit for ${request.key} (${it.text.length} chars)")
                return SpokenLine(it.text, it.style)
            }
        if (!settings.current.hasGeminiKey) return SpokenLine(request.authored, null)

        val jobKey = "$signature|$key"
        val deferred = jobs.getOrPut(jobKey) {
            _loading.value = _loading.value + request.key
            scope.async {
                val line = try {
                    rewrite(tourId, guide, request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Rewrite failed for ${request.key}; keeping the authored text", e)
                    null
                } finally {
                    _loading.value = _loading.value - request.key
                    jobs.remove(jobKey)
                }
                // Cache only a real rewrite, and only if nothing in the signature changed underneath.
                if (line != null && signatureFor(tourId) == signature) {
                    _narrations.value = _narrations.value + (key to GuideNarration(signature, line.text, line.style))
                    persist()
                    Log.i(TAG, "Rewrote ${request.key}: ${line.text.length} chars")
                }
                line ?: SpokenLine(request.authored, guide.style)
            }
        }
        return deferred.await()
    }

    private fun persist() {
        val snapshot = _narrations.value
        scope.launch(Dispatchers.IO) { store.save(snapshot) }
    }

    private suspend fun resolvedModel(): String =
        model ?: geminiClient.resolveModel(settings.current.geminiModel).also { model = it }

    private suspend fun requestGuides(
        tour: TourEntity,
        stops: List<StopEntity>,
        explorers: List<ExplorerType>,
    ): List<Guide> {
        val text = geminiClient.generate(
            model = resolvedModel(),
            systemInstruction = GUIDE_SYSTEM,
            history = emptyList(),
            prompt = buildString {
                if (explorers.isNotEmpty()) {
                    appendLine("The walker's interests, in order of priority: ${preferences(explorers)}.")
                } else {
                    appendLine("The walker has no stated preference; make the guides broadly different.")
                }
                appendLine("The tour is \"${tour.title}\" in ${tour.city}, ${tour.country}.")
                appendLine("Summary: ${tour.summary}")
                if (stops.isNotEmpty()) {
                    appendLine("The stops, in order: ${stops.joinToString(", ") { it.name }}.")
                }
                appendLine()
                appendLine("Invent exactly 5 distinct guide personalities to narrate this walk to that walker.")
                appendLine("Each must have a memorable human name — a first name plus a short epithet — that fits ${tour.city}, and a genuinely different temperament. No two should sound alike, and they should not be five versions of the same curious local.")
                appendLine("The set should collectively lean into the walker's priorities, with the strongest match to their top interest offered first.")
                append("Reply with ONLY a JSON array of 5 objects, each with keys name, tagline (max 12 words) and style (one sentence on how this guide speaks, for a narrator to imitate).")
            },
            maxOutputTokens = 1500,
            thinkingBudget = 0,
        )

        val json = JSONArray(extractJsonArray(text))
        return buildList {
            for (i in 0 until json.length()) {
                json.optJSONObject(i)?.let { obj -> Guide.fromJson(obj)?.let { add(it) } }
            }
        }
    }

    /** A ranked, human-readable list of the walker's interests for the prompts. */
    private fun preferences(explorers: List<ExplorerType>): String =
        explorers
            .take(MAX_EXPLORER_PREFERENCES)
            .mapIndexed { index, explorer -> "${index + 1}. ${explorer.label} (${explorer.brief})" }
            .joinToString("; ")

    private suspend fun rewrite(tourId: String, guide: Guide, request: NarrationRequest): SpokenLine {
        val persona = personaSettings.current
        val tour = repository.getTour(tourId)
        val raw = geminiClient.generate(
            model = resolvedModel(),
            systemInstruction = narrationSystem(tour, guide, persona.explorers, persona.tone(tourId), request.isIntroduction),
            history = emptyList(),
            prompt = buildString {
                appendLine(request.context + ".")
                appendLine("Rewrite this narration in your own voice, and direct the performance:")
                appendLine()
                append(request.authored)
            },
            maxOutputTokens = 3000,
            thinkingBudget = 0,
        )
        return parseSpokenLine(raw, guide)
            ?: throw IllegalStateException("The rewrite came back unusable")
    }

    private fun narrationSystem(
        tour: TourEntity?,
        guide: Guide,
        explorers: List<ExplorerType>,
        tone: String?,
        isIntroduction: Boolean,
    ): String = buildString {
        appendLine("You are ${guide.name}. ${guide.tagline}")
        appendLine("You are the voice of an audio walking tour in ${tour?.city ?: "this city"}.")
        appendLine("Speak in this style: ${guide.style}")
        if (!tone.isNullOrBlank()) {
            appendLine()
            appendLine("But the walker has redirected you: \"$tone\". Where that conflicts with the persona")
            appendLine("above, it wins — your voice, your asides, and how you introduce yourself all follow it.")
            appendLine("Apply it only where it belongs, and keep every fact, name and figure clear; never bend")
            appendLine("the truth to fit the bit.")
        }
        if (isIntroduction) {
            appendLine()
            appendLine("This is the walker's introduction to the whole walk. Open by introducing yourself in")
            appendLine("the character and voice you are speaking in now, in your own words, and welcome them")
            appendLine("to ${tour?.city ?: "the city"}. Then set up the walk ahead the way you tell things, not")
            appendLine("like a generic greeting.")
        }
        if (explorers.isNotEmpty()) {
            appendLine("The walker's interests, in order of priority: ${preferences(explorers)}. Let the first")
            appendLine("weigh most heavily, then the others; decide what you dwell on, what you cut and what")
            appendLine("you get excited about from all of them.")
        }
        appendLine()
        appendLine("You have complete freedom to rewrite the script however the telling demands. Restructure")
        appendLine("it. Change the emphasis, the order and the framing. Cut what drags, expand what sings,")
        appendLine("add your own asides, judgements and digressions. It should be unmistakably you, and")
        appendLine("unmistakably for this walker. If someone heard the original and yours, they should never")
        appendLine("think they were the same recording.")
        appendLine()
        appendLine("The only things to hold on to:")
        appendLine("- Stay truthful. Names, dates and places stay accurate, and do not invent facts, figures")
        appendLine("  or sights that were not there. Reframing is welcome; fabrication is not.")
        appendLine("- Aim for about three minutes spoken, roughly 400 to 450 words. Shorter beats longer.")
        appendLine("- Write for the ear: second person, present tense, plain prose. No markdown, no lists,")
        appendLine("  no headings, no bracketed stage directions.")
        appendLine("- Drop non-verbal sounds inline in the text where they happen, in angle brackets:")
        appendLine("  <laugh>, <sigh>, <breath>, <cough>, <short pause>. Use them sparingly, only where a real")
        appendLine("  teller would — never a tag in every sentence.")
        appendLine()
        appendLine("Also give a sustained style direction for the whole delivery, matching this guide and")
        appendLine("the mood of this stop (for example \"dry and conspiratorial, unhurried\").")
        appendLine()
        appendLine("Reply with ONLY a JSON object: {\"style\": \"...\", \"text\": \"...\"}. No code fences.")
    }

    /**
     * The model is asked for `{"style": "...", "text": "..."}`. It is tolerated if it answers with
     * plain prose instead — the whole reply becomes the text. A reply that looks like JSON but
     * cannot be parsed (truncated, say) yields null, so the caller retries rather than speaking
     * broken JSON at the walker.
     */
    private fun parseSpokenLine(raw: String, guide: Guide): SpokenLine? {
        val trimmed = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val jsonText = extractJsonObject(trimmed)
        if (jsonText != null) {
            val obj = runCatching { JSONObject(jsonText) }.getOrNull()
            val text = obj?.optString("text")?.trim().orEmpty()
            if (text.isNotBlank()) {
                val style = obj!!.optString("style").trim().ifBlank { guide.style }
                return SpokenLine(text, style)
            }
            // Malformed JSON: salvage the text field if it is recoverable, else give up.
            val salvaged = salvageTextField(jsonText) ?: return null
            return SpokenLine(salvaged, guide.style)
        }
        val prose = trimmed.removeSurrounding("\"").trim()
        return if (prose.isNotBlank()) SpokenLine(prose, guide.style) else null
    }

    /** Reads the value of `"text"` out of a JSON string that may have been cut off mid-way. */
    private fun salvageTextField(json: String): String? {
        val marker = Regex("\"text\"\\s*:\\s*\"").find(json) ?: return null
        val out = StringBuilder()
        var i = marker.range.last + 1
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                when (val next = json[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(next)
                }
                i += 2
                continue
            }
            if (ch == '"') break
            out.append(ch)
            i++
        }
        return out.toString().trim().ifBlank { null }
    }

    /** The model sometimes wraps JSON in a code fence despite being asked not to. Dig it out. */
    private fun extractJsonArray(raw: String): String {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        return if (start in 0 until end) cleaned.substring(start, end + 1) else "[]"
    }

    private fun extractJsonObject(raw: String): String? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start in 0 until end) cleaned.substring(start, end + 1) else null
    }

    private companion object {
        const val TAG = "GuideController"
        const val GUIDE_SYSTEM =
            "You design memorable tour-guide personalities for a walking-tour app. " +
                "You reply with ONLY a JSON array, with no prose and no code fences."
    }
}

/**
 * The on-screen transcript: the performance tags are for the voice, not the eye, so they come out.
 */
fun stripSpeechTags(text: String): String =
    text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
