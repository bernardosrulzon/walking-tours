package com.walkingtours.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A detour illustration, fetched at runtime and kept on disk.
 *
 * Detour topics are generated, not authored, so unlike stop photos there is no bundled asset to
 * show. The illustration comes from Wikimedia Commons instead: searched by the topic's own query,
 * first freely-licensed JPEG wins, cached under the topic id. The credit travels in a sidecar file
 * next to the image and is shown under it, per the Commons terms.
 */
data class DetourPhoto(val file: File, val credit: String)

/**
 * Returns the cached illustration for this topic, fetching it on first use. Null when offline, when
 * nothing suitably licensed turns up, or when anything else goes wrong — the detour page shows an
 * empty panel of the same size instead, so the text still works.
 */
suspend fun fetchDetourPhoto(context: Context, topicId: String, query: String): DetourPhoto? =
    withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "detour-images").apply { mkdirs() }
            val safe = topicId.replace(Regex("[^a-z0-9-]"), "-").trim('-').take(40)
                .ifBlank { "detour" }
            val image = File(dir, "$safe.jpg")
            val meta = File(dir, "$safe.json")
            if (image.exists() && image.length() > 0) {
                val credit = runCatching { JSONObject(meta.readText()).optString("credit") }
                    .getOrNull().orEmpty()
                return@withContext DetourPhoto(image, credit)
            }

            val candidate = searchCommons(query) ?: return@withContext null
            val bytes = downloadBytes(candidate.thumbUrl) ?: return@withContext null
            val tmp = File(dir, "$safe.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(image)) {
                tmp.copyTo(image, overwrite = true)
                tmp.delete()
            }
            meta.writeText(JSONObject().put("credit", candidate.credit).toString())
            DetourPhoto(image, candidate.credit)
        } catch (e: Exception) {
            Log.w(TAG, "Detour photo failed for '$query'", e)
            null
        }
    }

private data class CommonsHit(val thumbUrl: String, val credit: String)

private fun searchCommons(query: String): CommonsHit? {
    // Bitmaps only: the player below decodes JPEG, and SVGs/animations have no place as a hero.
    val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
        "&generator=search&gsrsearch=${urlEncode("$query filetype:bitmap")}&gsrnamespace=6&gsrlimit=10" +
        "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=1280"
    val json = JSONObject(getText(url))
    val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
    for (key in pages.keys()) {
        val page = pages.optJSONObject(key) ?: continue
        val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
        if (info.optString("mime") != "image/jpeg") continue
        val thumb = info.optString("thumburl").takeIf { it.isNotBlank() } ?: continue
        val meta = info.optJSONObject("extmetadata") ?: continue
        val short = meta.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
        val terms = meta.optJSONObject("UsageTerms")?.optString("value").orEmpty()
        if (!licenseOk(short, terms)) continue
        val artist = cleanHtml(meta.optJSONObject("Artist")?.optString("value").orEmpty())
            .ifBlank { "Unknown photographer" }
        val license = short.ifBlank { "freely licensed" }
        val source = "https://commons.wikimedia.org/wiki/" +
            urlEncode(page.optString("title").replace(' ', '_'))
        val credit = "Photograph: $artist, $license, via Wikimedia Commons ($source)"
        return CommonsHit(thumb, credit)
    }
    return null
}

/**
 * Conservative allowlist: public domain, CC0 and plain CC BY / BY-SA pass; anything with a
 * non-commercial or no-derivatives condition — or an unrecognised licence — does not.
 */
private fun licenseOk(short: String, terms: String): Boolean {
    if (Regex("""(?i)^(public domain|cc0|cc by|cc by-sa)(\s|$)""").containsMatchIn(short.trim())) {
        return true
    }
    if (terms.contains("NonCommercial", ignoreCase = true) ||
        terms.contains("NoDerivs", ignoreCase = true) ||
        terms.contains("NoDerivatives", ignoreCase = true)
    ) {
        return false
    }
    if (terms.contains("Public Domain", ignoreCase = true)) return true
    return Regex("""Creative Commons Attribution(-Share Alike)? \d""").containsMatchIn(terms)
}

private fun cleanHtml(raw: String): String = raw
    .replace(Regex("<[^>]*>"), "")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#039;", "'")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun getText(url: String): String {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            // A User-Agent keeps Commons' abuse filters calm about script traffic.
            setRequestProperty("User-Agent", "WalkingToursApp/1.0 (personal walking-tour app)")
        }
        if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection?.disconnect()
    }
}

private fun downloadBytes(url: String): ByteArray? {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        if (connection.responseCode !in 200..299) return null
        val bytes = connection.inputStream.use { it.readBytes() }
        // Thumbnails at this width land well under a megabyte; refuse anything absurd instead of
        // filling the disk with it.
        return bytes.takeIf { it.isNotEmpty() && it.size <= 8 * 1024 * 1024 }
    } catch (e: IOException) {
        Log.w(TAG, "Detour photo download failed", e)
        return null
    } finally {
        connection?.disconnect()
    }
}

private const val TAG = "DetourImages"
