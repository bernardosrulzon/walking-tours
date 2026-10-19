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
 * The model used through OpenCode.
 *
 * Same model as the Google transport, on OpenCode's Gemini-compatible path: the app keeps one
 * request and response shape and only the transport changes. Note that the **Go** subscription does
 * not serve Gemini 3.8 Flash (its only Gemini model is Gemini 4 Argon, which this app does not use);
 * this is OpenCode's pay-as-you-go Console family, and needs Console credits.
 */
const val OPENCODE_MODEL = "gemini-3.8-flash"

/** Identifies this app to OpenCode — a product name, not a generic SDK or HTTP library. */
const val MODEL_USER_AGENT = "walking-tours/1.0"

/**
 * The text-model client: guides, rewrites, detours and chat.
 *
 * It talks to one of two providers, chosen in Settings and defaulting to Google Gemini, since
 * both run Gemini 3.8 Flash and Google works without extra credits:
 *
 *  - **OpenCode** (`https://opencode.ai/inference/...`) with a service-account key. The gateway asks
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
        val body = buildBody(
            systemInstruction = systemInstruction,
            history = history,
            prompt = prompt,
            images = images,
            maxOutputTokens = maxOutputTokens,
            thinkingBudget = thinkingBudget,
        )

        val json = if (state.modelProvider == MODEL_PROVIDER_OPENCODE) {
            val key = requireOpenCodeKey(state)
            AiHttp.postJson(
                "$OPENCODE_BASE/models/$OPENCODE_MODEL:generateContent",
                body,
                openCodeHeaders(state, key, session),
                readTimeoutMs = GENERATE_TIMEOUT_MS,
                serviceName = "OpenCode",
            )
        } else {
            val key = requireGoogleKey(state)
            AiHttp.postJson(
                "$GOOGLE_BASE/models/$TEXT_MODEL:generateContent?key=$key",
                body,
                AppIdentityHeaders.build(context),
                readTimeoutMs = GENERATE_TIMEOUT_MS,
            )
        }

        return decodeAnswer(json)
    }

    private fun buildBody(
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

    private fun decodeAnswer(json: JSONObject): String {
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
        const val OPENCODE_BASE = "https://opencode.ai/inference/google/v1beta"
        const val OPENCODE_MODELS_URL = "https://opencode.ai/inference/v1/models"
        const val MAX_HISTORY_TURNS = 8

        /**
         * Rewrites of a full stop run to tens of seconds even with thinking off, so generation gets
         * a longer read timeout than the default chat call.
         */
        const val GENERATE_TIMEOUT_MS = 120_000
    }
}
