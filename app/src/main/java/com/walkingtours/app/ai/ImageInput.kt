package com.walkingtours.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** An image to send inline with a Gemini request. */
data class InlineImage(val mimeType: String, val base64: String)

/**
 * Reads [uri] and returns a downscaled JPEG ready to send, or null if it cannot be read.
 *
 * Downscaled to [maxEdgePx] because the model does not need more and the request is paid for by the
 * pixel — a photo straight off a modern phone camera is many megabytes.
 */
fun loadInlineImage(context: Context, uri: Uri, maxEdgePx: Int = 1024): InlineImage? = runCatching {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx)
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return@runCatching null

    val scaled = scaleDown(decoded, maxEdgePx)
    val bytes = ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    }
    if (scaled !== decoded) decoded.recycle()
    scaled.recycle()

    InlineImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
}.getOrNull()

/** A small bitmap for an on-screen thumbnail, or null. */
fun decodePreviewBitmap(context: Context, uri: Uri, maxEdgePx: Int = 240): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx)
    }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= maxEdge && h / 2 >= maxEdge) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxEdge) return bitmap
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}
