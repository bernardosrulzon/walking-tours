package com.walkingtours.app.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A failed Google API call, carrying a message already phrased for the user.
 */
class AiException(
    message: String,
    val httpCode: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Supplies the two headers Google needs to verify that a request comes from the app it claims to.
 *
 * This is what makes an "Android apps" API key restriction actually meaningful: without them, a
 * key restricted to our package name and signing certificate would be rejected. They are built from
 * the running app's own signature, so a key copied out of the APK cannot be used from anywhere else.
 */
object AppIdentityHeaders {

    fun build(context: Context): Map<String, String> {
        val cert = signingCertificateSha1(context) ?: return emptyMap()
        return mapOf(
            "X-Android-Package" to context.packageName,
            "X-Android-Cert" to cert,
        )
    }

    private fun signingCertificateSha1(context: Context): String? = try {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val bytes = signatures?.firstOrNull()?.toByteArray() ?: return null
        MessageDigest.getInstance("SHA-1").digest(bytes)
            // Google expects uppercase hex with no separators.
            .joinToString("") { "%02X".format(it) }
    } catch (e: Exception) {
        null
    }
}

/**
 * Minimal JSON-over-HTTPS helper for the Google APIs.
 *
 * Hand-rolled on HttpURLConnection rather than pulling in a networking library: the app makes two
 * kinds of call, both simple, and this keeps the APK and the dependency surface small.
 */
internal object AiHttp {

    private const val TIMEOUT_MS = 30_000

    suspend fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        request("POST", url, body.toString(), headers)
    }

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        request("GET", url, null, headers)
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) doOutput = true
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) throw AiException(friendlyMessage(code, text), code)

            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException("Could not reach Google. Check your internet connection.", 0, e)
        } finally {
            connection?.disconnect()
        }
    }

    /** Turns an HTTP failure into something a walker standing in a square can act on. */
    private fun friendlyMessage(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()

        val base = when (code) {
            400 -> "Google rejected the request."
            401, 403 -> "Google rejected the credentials. Check the API key, and that the API is " +
                "enabled for your project with billing turned on."
            404 -> "Not found. The model or voice may not be available to your account."
            429 -> "Quota exceeded. Wait a moment, or review your Google Cloud quotas and billing."
            in 500..599 -> "Google's service is temporarily unavailable. Try again."
            else -> "Request failed with HTTP $code."
        }
        return if (detail.isNullOrBlank()) base else "$base\n\n$detail"
    }
}
