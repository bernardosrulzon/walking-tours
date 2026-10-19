package com.walkingtours.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    /** Set when the failure came from us rather than the model, so the UI can style it. */
    val isError: Boolean = false,
)

/**
 * The model used when the walker talks to Google directly.
 *
 * Pinned deliberately: discovery used to pick the newest model the key could see, which silently
 * moved the app onto Gemini 4 Argon the day it appeared. Cost is part of the design, so the model is
 * a constant.
 */
const val TEXT_MODEL = "gemini-3.8-flash"

/**
 * The model used through OpenCode Go: DeepSeek V4.1 Flash.
 *
 * Go serves it on the OpenAI Chat Completions API, so the OpenCode transport speaks that shape
 * rather than Gemini's. The Go plan includes it, it accepts images, and it follows the app's JSON
 * contracts. It reasons by default, but `reasoning_effort: "none"` switches that off for the
 * structured rewrites, where hidden thinking would otherwise eat the output budget.
 */
const val OPENCODE_MODEL = "deepseek-v4.1-flash"

/** Identifies this app to OpenCode — a product name, not a generic SDK or HTTP library. */
const val MODEL_USER_AGENT = "walking-tours/1.0"

/**
 * The text-model client: guides, rewrites, detours and chat.
 *
 * It talks to one of two providers, chosen in Settings and defaulting to Google Gemini, since
 * both run Gemini 3.8 Flash and Google works without extra credits:
 *
 *  - **OpenCode Go** (`https://opencode.ai/inference/go/...`) with a service-account key. Go asks
 *    clients to identify themselves with their own User-Agent and to send a stable
 *    `x-opencode-session` per conversation, which is what [generate]'s `session` argument is for.
 *  - **Google Gemini** directly, with the Google key and the app-identity headers.
 *
 * Both speak the same Gemini request/response shape, so everything above this class is unaware of
 * the choice.
 */
class ModelClient(
    private val context: Context,
    private val settings: AiSettings,
) {

    /** The model id the active provider will call, for Settings and the key check. */
    val activeModel: String
        get() = if (settings.current.modelProvider == MODEL_PROVIDER_OPENCODE) OPENCODE_MODEL else TEXT_MODEL

    /** Model ids the active provider offers. Used to check the key and the pinned model. */
    suspend fun availableModels(): List<String> {
        val state = settings.current
        if (state.modelProvider == MODEL_PROVIDER_OPENCODE) {
            val key = requireOpenCodeKey(state)
            val json = AiHttp.getJson(
                OPENCODE_MODELS_URL,
                openCodeHeaders(state, key, session = "models"),
                serviceName = "OpenCode",
            )
            val data = json.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (i in 0 until data.length()) {
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }

        val key = requireGoogleKey(state)
        val json = AiHttp.getJson("$GOOGLE_BASE/models?key=$key", AppIdentityHeaders.build(context))
        val models = json.optJSONArray("models") ?: return emptyList()
        return buildList {
            for (i in 0 until models.length()) {
                val model = models.optJSONObject(i) ?: continue
                val methods = model.optJSONArray("supportedGenerationMethods")
                val canGenerate = methods != null &&
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (!canGenerate) continue
                model.optString("name").takeIf { it.isNotBlank() }?.removePrefix("models/")?.let { add(it) }
            }
        }
    }

    suspend fun generate(
        systemInstruction: String,
        history: List<ChatMessage>,
        prompt: String,
        maxOutputTokens: Int = 700,
        /**
         * Limits the model's hidden reasoning. The narration and guide rewrites are short,
         * structured and cost-sensitive, and thinking otherwise eats the whole output budget and
         * truncates the JSON; passing 0 switches it off. Null leaves the model's default.
         */
        thinkingBudget: Int? = null,
        /** Images to send with the prompt, e.g. a photo the walker wants identified. */
        images: List<InlineImage> = emptyList(),
        /**
         * Stable id of the conversation this request belongs to. Sent to OpenCode Go as
         * `x-opencode-session` so related requests are routed together and prompt caching works;
         * ignored by the Google transport.
         */
        session: String = "default",
    ): String {
        val state = settings.current
        val body = buildGeminiBody(
            systemInstruction = systemInstruction,
            history = history,
            prompt = prompt,
            images = images,
            maxOutputTokens = maxOutputTokens,
            thinkingBudget = thinkingBudget,
        )

        if (state.modelProvider == MODEL_PROVIDER_OPENCODE) {
            val key = requireOpenCodeKey(state)
            val json = AiHttp.postJson(
                OPENCODE_CHAT_URL,
                buildOpenAiBody(
                    systemInstruction = systemInstruction,
                    history = history,
                    prompt = prompt,
                    images = images,
                    maxOutputTokens = maxOutputTokens,
                    thinkingBudget = thinkingBudget,
                ),
                openCodeHeaders(state, key, session),
                readTimeoutMs = GENERATE_TIMEOUT_MS,
                serviceName = "OpenCode",
            )
            return decodeOpenAiAnswer(json)
        }

        val key = requireGoogleKey(state)
        val json = AiHttp.postJson(
            "$GOOGLE_BASE/models/$TEXT_MODEL:generateContent?key=$key",
            body,
            AppIdentityHeaders.build(context),
            readTimeoutMs = GENERATE_TIMEOUT_MS,
        )
        return decodeGeminiAnswer(json)
    }

    private fun buildGeminiBody(
        systemInstruction: String,
        history: List<ChatMessage>,
        prompt: String,
        images: List<InlineImage>,
        maxOutputTokens: Int,
        thinkingBudget: Int?,
    ): JSONObject {
        val contents = JSONArray().apply {
            // Keep the tail of the conversation so follow-up questions make sense, but bound it so
            // a long walk down a pier cannot blow up the request size.
            history.takeLast(MAX_HISTORY_TURNS).forEach { message ->
                if (message.isError) return@forEach
                put(
                    JSONObject().apply {
                        put("role", if (message.role == ChatRole.USER) "user" else "model")
                        put("parts", JSONArray().put(JSONObject().put("text", message.text)))
                    },
                )
            }
            val userParts = JSONArray().apply {
                if (prompt.isNotBlank()) {
                    put(JSONObject().put("text", prompt))
                }
                images.forEach { image ->
                    put(
                        JSONObject().apply {
                            put(
                                "inline_data",
                                JSONObject()
                                    .put("mime_type", image.mimeType)
                                    .put("data", image.base64),
                            )
                        },
                    )
                }
            }
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", userParts)
                },
            )
        }

        return JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
            )
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject().apply {
                    // Only the token cap. Google has deprecated temperature, top_p, top_k and
                    // thinking_budget as generation parameters, and sending them now draws a
                    // deprecation notice; the model's own defaults are used instead.
                    put("maxOutputTokens", maxOutputTokens)
                    if (thinkingBudget != null) {
                        put("thinkingConfig", JSONObject().put("thinkingBudget", thinkingBudget))
                    }
                },
            )
        }
    }

    /** The OpenAI Chat Completions shape: system + history + one user turn, with data-URL images. */
    private fun buildOpenAiBody(
        systemInstruction: String,
        history: List<ChatMessage>,
        prompt: String,
        images: List<InlineImage>,
        maxOutputTokens: Int,
        thinkingBudget: Int?,
    ): JSONObject {
        val messages = JSONArray().apply {
            if (systemInstruction.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemInstruction))
            }
            history.takeLast(MAX_HISTORY_TURNS).forEach { message ->
                if (message.isError) return@forEach
                put(
                    JSONObject().put(
                        "role",
                        if (message.role == ChatRole.USER) "user" else "assistant",
                    ).put("content", message.text),
                )
            }
            val userContent: Any = if (images.isEmpty()) {
                prompt
            } else {
                JSONArray().apply {
                    if (prompt.isNotBlank()) {
                        put(JSONObject().put("type", "text").put("text", prompt))
                    }
                    images.forEach { image ->
                        put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put(
                                        "url",
                                        "data:${image.mimeType};base64,${image.base64}",
                                    ),
                                ),
                        )
                    }
                }
            }
            put(JSONObject().put("role", "user").put("content", userContent))
        }

        return JSONObject().apply {
            put("model", OPENCODE_MODEL)
            put("messages", messages)
            put("max_tokens", maxOutputTokens)
            // The app asks for thinking off on every structured call; DeepSeek reasons by default,
            // and its reasoning tokens share the output budget.
            if (thinkingBudget == 0) put("reasoning_effort", "none")
        }
    }

    private fun decodeOpenAiAnswer(json: JSONObject): String {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiException("The model returned no answer. Try rephrasing the question.")
        val content = choice.optJSONObject("message")?.optString("content").orEmpty().trim()
        if (content.isBlank()) {
            throw AiException(
                if (choice.optString("finish_reason") == "length") {
                    "The model ran out of room before answering. Try a shorter question."
                } else {
                    "The model returned an empty answer."
                },
            )
        }
        return content
    }

    private fun decodeGeminiAnswer(json: JSONObject): String {
        // A safety block produces no candidates at all; say so plainly rather than showing "no reply".
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw AiException("That question was blocked by the provider's safety filters ($it).")
        }

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw AiException("The model returned no answer. Try rephrasing the question.")
        }
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw AiException("The model returned an unreadable answer.")

        val text = buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }.trim()

        if (text.isBlank()) throw AiException("The model returned an empty answer.")
        return text
    }

    /**
     * Headers the OpenCode gateway asks for: a bearer token, this client's own User-Agent, and a
     * stable session id so related requests share routing and prompt cache.
     */
    private fun openCodeHeaders(
        state: AiSettingsState,
        key: String,
        session: String,
    ): Map<String, String> = mapOf(
        "Authorization" to "Bearer $key",
        "User-Agent" to MODEL_USER_AGENT,
        "x-opencode-session" to openCodeSession(state.installId, session),
    )

    private fun openCodeSession(installId: String, session: String): String {
        val stable = installId.replace("-", "").take(8).ifBlank { "install" }
        val clean = session.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .take(90)
            .ifBlank { "default" }
        return "wt-$stable-$clean"
    }

    private fun requireGoogleKey(state: AiSettingsState): String {
        if (state.geminiApiKey.isBlank()) throw AiException("No Gemini API key is configured.")
        return state.geminiApiKey
    }

    private fun requireOpenCodeKey(state: AiSettingsState): String {
        if (state.opencodeApiKey.isBlank()) {
            throw AiException("No OpenCode API key is configured.")
        }
        return state.opencodeApiKey
    }

    private companion object {
        const val GOOGLE_BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val OPENCODE_CHAT_URL = "https://opencode.ai/inference/go/openai/v1/chat/completions"
        const val OPENCODE_MODELS_URL = "https://opencode.ai/inference/go/v1/models"
        const val MAX_HISTORY_TURNS = 8

        /**
         * Rewrites of a full stop run to tens of seconds even with thinking off, so generation gets
         * a longer read timeout than the default chat call.
         */
        const val GENERATE_TIMEOUT_MS = 120_000
    }
}
