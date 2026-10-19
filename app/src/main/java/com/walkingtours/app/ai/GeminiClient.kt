package com.walkingtours.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A model the account can call. */
data class GeminiModel(
    val name: String,
    val displayName: String,
) {
    /** "models/gemini-3.8-flash" -> "gemini-3.8-flash" */
    val id: String get() = name.removePrefix("models/")
}

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    /** Set when the failure came from us rather than the model, so the UI can style it. */
    val isError: Boolean = false,
)

/**
 * The one text model this app calls: Gemini 3.8 Flash.
 *
 * Pinned rather than discovered. Discovery used to pick the newest model the key could see, which
 * silently moved narration, guides, detours and chat onto Gemini 4 Argon the day it appeared — a
 * model several times the price. Every text call goes through [GeminiClient.generate], which uses
 * this constant, so there is one place to change it if the model is ever retired.
 */
const val TEXT_MODEL = "gemini-3.8-flash"

/**
 * Gemini API client.
 *
 * The model is pinned to [TEXT_MODEL] on purpose. Google retires model ids, and it also ships newer
 * ones, so a "pick the newest the key can see" rule silently moves the whole app onto whatever
 * expensive model was released most recently — that is how narration and chat ended up billed against
 * Gemini 4 Argon. Cost is part of this app's design, so the model is a constant, not a discovery.
 * [listModels] is still used, but only to check that the key and the pinned model are alive.
 */
class GeminiClient(
    private val context: Context,
    private val apiKey: () -> String,
) {

    suspend fun listModels(): List<GeminiModel> {
        val key = requireKey()
        val json = AiHttp.getJson("$BASE_URL/models?key=$key", AppIdentityHeaders.build(context))
        val array = json.optJSONArray("models") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val model = array.optJSONObject(i) ?: continue
                val name = model.optString("name")
                if (name.isBlank()) continue
                val methods = model.optJSONArray("supportedGenerationMethods")
                val supportsGenerate = methods != null &&
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (!supportsGenerate) continue
                add(GeminiModel(name = name, displayName = model.optString("displayName", name)))
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
    ): String {
        val key = requireKey()
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

        val body = JSONObject().apply {
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

        val json = AiHttp.postJson(
            "$BASE_URL/models/$TEXT_MODEL:generateContent?key=$key",
            body,
            AppIdentityHeaders.build(context),
            readTimeoutMs = GENERATE_TIMEOUT_MS,
        )

        // A safety block produces no candidates at all; say so plainly rather than showing "no reply".
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw AiException("That question was blocked by Google's safety filters ($it).")
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

    private fun requireKey(): String {
        val key = apiKey()
        if (key.isBlank()) {
            throw AiException("No Gemini API key is configured.")
        }
        return key
    }

    private companion object {
        const val TAG = "GeminiClient"
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val MAX_HISTORY_TURNS = 8

        /**
         * Rewrites of a full stop run to tens of seconds even with thinking off, so generation gets
         * a longer read timeout than the default chat call.
         */
        const val GENERATE_TIMEOUT_MS = 120_000
    }
}
