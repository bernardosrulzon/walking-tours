package com.walkingtours.app.ai

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * On-disk store of generated narrations, so a rewrite is paid for once and survives the process.
 *
 * Keyed by "tourId|narrationKey". Each entry carries the [GuideNarration.signature] it was generated
 * under, so a change to the guide, the walker's preferences or the tone simply makes the old entry
 * miss rather than having to be hunted down and deleted.
 */
class NarrationStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun load(): Map<String, GuideNarration> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            buildMap {
                root.keys().forEach { key ->
                    val entry = root.optJSONObject(key) ?: return@forEach
                    val text = entry.optString("text")
                    val signature = entry.optString("signature")
                    if (text.isNotBlank() && signature.isNotBlank()) {
                        put(
                            key,
                            GuideNarration(
                                signature = signature,
                                text = text,
                                style = entry.optString("style").ifBlank { null },
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun save(entries: Map<String, GuideNarration>) {
        runCatching {
            val root = JSONObject()
            entries.forEach { (key, narration) ->
                root.put(
                    key,
                    JSONObject()
                        .put("signature", narration.signature)
                        .put("text", narration.text)
                        .put("style", narration.style.orEmpty()),
                )
            }
            // Write then rename, so an interrupted write cannot leave a half-written cache.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "narration_cache.json"
    }
}
