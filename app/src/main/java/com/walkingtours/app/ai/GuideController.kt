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
const val NARRATION_PROMPT_VERSION = "10"

fun guideSignature(
    guide: Guide?,
    explorers: List<ExplorerType>,
    tone: String?,
    language: String = NARRATION_LANGUAGE,
): String? =
    guide?.let {
        listOf(
            NARRATION_PROMPT_VERSION,
            it.id,
            it.style,
            explorers.joinToString(",") { explorer -> explorer.id },
            tone.orEmpty(),
            language,
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

    /**
     * Suggested guides by "tour|preferences|language". Preferences are part of the key, so changing
     * them regenerates; otherwise the picker reuses the last answer instead of paying for it again.
     * Only successful generations are cached — a fallback is never stored, so a transient failure
     * still retries next time.
     */
    private val guideSuggestions = mutableMapOf<String, List<Guide>>()

    private var model: String? = null

    /** The signature the current guide, preferences and tone produce for [tourId], or null. */
    fun signatureFor(tourId: String): String? {
        val persona = personaSettings.current
        return guideSignature(
            persona.guide(tourId),
            persona.explorers,
            persona.tone(tourId),
            settings.current.narrationLanguage,
        )
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
     * Four guide options for [tourId] and these ranked preferences, or the built-in fallbacks when
     * the model cannot be reached. Never throws.
     */
    suspend fun suggestGuides(tourId: String, explorers: List<ExplorerType>): List<Guide> {
        val language = settings.current.narrationLanguage
        val cacheId = "$tourId|${explorers.joinToString(",") { it.id }}|$language"
        guideSuggestions[cacheId]?.let { return it }
        if (!settings.current.hasGeminiKey) return fallbackGuides(language)
        val tour = runCatching { repository.getTour(tourId) }.getOrNull() ?: return fallbackGuides(language)
        val stops = runCatching { repository.getStops(tourId) }.getOrDefault(emptyList())
        val guides = runCatching { requestGuides(tour, stops, explorers, language) }
            .onFailure { Log.w(TAG, "Guide suggestion failed; using fallback guides", it) }
            .getOrNull()
            ?.takeIf { it.size >= 3 }
        if (guides != null) guideSuggestions[cacheId] = guides
        return guides ?: fallbackGuides(language)
    }

    /**
     * The voice the tour's guide must speak with, or null when there is no guide to match. The
     * TTS voice and the guide's gender are bound together: a feminine guide never speaks with a
     * masculine voice, whatever is configured.
     */
    fun voiceForGuide(tourId: String): String? {
        val guide = personaSettings.current.guide(tourId) ?: return null
        return voiceForGuide(guide, settings.current.cloudVoiceName)
    }

    /**
     * Point the configured voice at the tour guide's gender. No-op when it already matches, so this
     * is safe to call before every play: the setting — and the engine built from it — only changes
     * on a real mismatch.
     */
    fun ensureVoiceMatchesGuide(tourId: String) {
        val voice = voiceForGuide(tourId) ?: return
        if (settings.current.cloudVoiceName != voice) {
            settings.update { it.copy(cloudVoiceName = voice) }
        }
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
        val signature = guideSignature(
            guide,
            persona.explorers,
            persona.tone(tourId),
            settings.current.narrationLanguage,
        ) ?: return SpokenLine(request.authored, null)
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
        language: String,
    ): List<Guide> {
        val languageName = if (language == NARRATION_LANGUAGE_PT_BR) {
            "Brazilian Portuguese"
        } else {
            "English"
        }
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
                appendLine("Invent exactly 4 distinct guide personalities to narrate this walk to that walker: 2 contemporary, everyday guides (a local, a professional, a neighbor — people who could plausibly walk this route today) and 2 voices from old professions that belong to this place and era (a ferryman, a scribe, a spice merchant — never an archivist, curator, or historian).")
                appendLine("Each must have a memorable human name — a first name plus a short epithet — that fits ${tour.city}, and a genuinely different temperament. No two should sound alike, and they should not be four versions of the same curious local; the old-profession voices must feel lived-in, not academic: they worked here, they did not study it.")
                appendLine("Keep every character grounded and instantly legible to a newcomer: recognizable people, not exotic caricatures, and plain words throughout the name, epithet and tagline. Never use an insider profession term as the epithet — \"boatman\" is clear, \"majhi\" means nothing to someone choosing a guide; \"tea seller\", not \"chaiwale\"; \"pilgrim priest\", not \"tirtha purohit\". If a local word appears at all, its meaning must be obvious from the words around it.")
                appendLine("The set should collectively lean into the walker's priorities, with the strongest match to their top interest offered first.")
                appendLine("But the place comes first. Every guide must make sense for this tour and this place — a chef would be absurd at an aviation museum, a mystic odd in a rose garden. The walker's interests are a lens on the place, never a reason to pick a guide whose character does not fit it.")
                appendLine("Write each guide's name and tagline in $languageName, idiomatic and natural in that language — a Portuguese epithet, not a translation of an English one. The \"style\" field stays in English, as an instruction to the narrator.")
                append("Reply with ONLY a JSON array of 4 objects, each with keys name, tagline (max 12 words), style (one sentence on how this guide speaks, for a narrator to imitate) and gender (\"feminine\" or \"masculine\", whichever the character is).")
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
            systemInstruction = narrationSystem(
                tour,
                guide,
                persona.explorers,
                persona.tone(tourId),
                request.isIntroduction,
                settings.current.narrationLanguage,
            ),
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
        language: String,
    ): String = buildString {
        val portuguese = language == NARRATION_LANGUAGE_PT_BR
        appendLine("You are ${guide.name}. ${guide.tagline}")
        appendLine("You are the voice of an audio walking tour in ${tour?.city ?: "this city"}.")
        appendLine("Speak in this style: ${guide.style}")
        if (portuguese) {
            appendLine()
            appendLine("Write the entire narration in Brazilian Portuguese (português do Brasil): every")
            appendLine("word the walker hears and reads is Portuguese. Keep proper names of people,")
            appendLine("places and monuments in their original form. The inline vocal tags stay exactly")
            appendLine("as they are (<laugh>, <sigh>, <breath>, <cough>, <short pause>), and the \"style\"")
            appendLine("direction you return stays in English.")
            appendLine()
            appendLine("Important: do NOT translate the English script sentence by sentence. That produces")
            appendLine("stiff, foreign-sounding Portuguese. Read the script for the facts only, then tell")
            appendLine("the story fresh, the way a gifted Brazilian storyteller would speak it aloud:")
            appendLine("Brazilian rhythm and word order, natural colloquialisms where this guide's voice")
            appendLine("calls for them, idioms that land in Portuguese rather than calques of English")
            appendLine("ones. Restructure, merge and split sentences freely until nothing sounds translated.")
            appendLine("Render your own name and epithet in Portuguese as well — \"Meryem the Storyteller\"")
            appendLine("becomes \"Meryem, a Contadora de Histórias\" — so you introduce yourself in the same tongue.")
        }
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
        } else {
            appendLine()
            appendLine("Do not introduce yourself and do not greet the walker: the introduction already did")
            appendLine("that. Start straight in on the place in front of them.")
        }
        if (explorers.isNotEmpty()) {
            appendLine("The walker's interests, in order of priority: ${preferences(explorers)}. Let the first")
            appendLine("weigh most heavily, then the others; decide what you dwell on, what you cut and what")
            appendLine("you get excited about from all of them.")
        }
        appendLine("The place comes first. Your interests and any adjustment shape what you notice and how")
        appendLine("you tell it, but never force a topic, a joke or a tone that does not fit what is actually")
        appendLine("here. If an interest has nothing to say about this stop, let it go and tell the stop well.")
        appendLine()
        appendLine("Adapt the script to your voice and experience: restructure it, change the emphasis")
        appendLine("and the framing, cut what drags, add relevant color and asides only you would know. But the")
        appendLine("fallback script is the spine — keep its storyline, its stops and its facts. Adapt it, do not")
        appendLine("overhaul it: a walker who heard both versions should recognize the same walk, told by you.")
        appendLine()
        appendLine("Stay in character the whole way, but do not overdo it. The persona is a voice and a way")
        appendLine("of noticing, not a costume: speak as the character would, and let the performance serve")
        appendLine("the place rather than itself. Never force a joke, an accent tic or a catchphrase where it")
        appendLine("does not belong, and never let the act bury the facts.")
        appendLine()
        appendLine("Hold every part to this bar: when the stop ends, the walker should feel they learned")
        appendLine("something real — a mechanism, a belief, a tension, a story that reframes the place. That")
        appendLine("is balance, not density: plain sentences are fine when they carry something. Cut")
        appendLine("platitudes (generic wonder, adjectives standing in for observation), throat-clearing")
        appendLine("openers, stage directions that direct nothing, and descriptions where each clause teaches")
        appendLine("nothing new. Humor and irreverence are delivery, never a substitute: every joke must land")
        appendLine("on a fact.")
        appendLine()
        appendLine("The only things to hold on to:")
        appendLine("- Stay truthful. Names, dates and places stay accurate, and do not invent facts, figures")
        appendLine("  or sights that were not there. Reframing is welcome; fabrication is not.")
        appendLine("- Assume the walker knows nothing about this city or country. Explain every local term,")
        appendLine("  currency unit, historical actor and religious concept inline, on first use, in a breath:")
        appendLine("  who the Mughals were, what a lakh is, what a panda does, what moksha promises. Never")
        appendLine("  use a word the walker cannot be expected to know without unpacking it right there.")
        appendLine("- Aim for about three minutes spoken, roughly 400 to 450 words. Shorter beats longer —")
        appendLine("  except the introduction, which may run to about five minutes when the city's context")
        appendLine("  demands it. Clarity first, always.")
        appendLine("- Write for the ear: second person, present tense, plain prose. No markdown, no lists,")
        appendLine("  no headings, no bracketed stage directions. Keep a blank line between paragraphs —")
        appendLine("  the transcript shows them as separate paragraphs.")
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
 * Paragraph breaks survive: collapsing them is what turned the transcript into a wall of text.
 */
fun stripSpeechTags(text: String): String =
    text.replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("(?:[ \\t]*\\n){2,}"), "\n\n")
        .trim()
