package com.walkingtours.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image

/**
 * Decodes a bundled asset photograph without pulling in an image-loading library.
 *
 * The images ship inside the APK, so there is no network, no cache and no cancellation problem to
 * solve — a straight background decode into an [ImageBitmap] is enough. Downsampling during decode
 * keeps memory sane: a 1200px JPEG is decoded at roughly the width it will be displayed at.
 */
@Composable
fun AssetPhoto(
    assetPath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    targetWidthPx: Int = 1080,
) {
    val context = LocalContext.current
    var bitmap by remember(assetPath) { mutableStateOf(AssetPhotoCache[assetPath]) }
    var unavailable by remember(assetPath) { mutableStateOf(false) }

    LaunchedEffect(assetPath) {
        val path = assetPath ?: return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect
        unavailable = false
        val decoded = withContext(Dispatchers.IO) { decodeAsset(context, path, targetWidthPx) }
        if (decoded != null) {
            AssetPhotoCache[path] = decoded
            bitmap = decoded
        } else {
            unavailable = true
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            // Only a photograph that is genuinely missing gets a message. The three other states —
            // no stop yet, a decode in flight, a page whose stop arrived a frame later — all used to
            // print "Photo unavailable", which read as a fault on every tour start: the stop list
            // loads asynchronously, so the hero is built with no stop and then given one.
            if (unavailable) {
                Text(
                    text = "Photo unavailable",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Decoded photographs, keyed by asset path, for the life of the process.
 *
 * The tour ships a handful of images and they are decoded once each, so holding them costs a few
 * megabytes. Without this, every scroll back to a page re-decoded its JPEG, and the delay showed on
 * screen each time.
 */
private object AssetPhotoCache {
    private const val MAX_ENTRIES = 8
    private val entries = LinkedHashMap<String, ImageBitmap>()

    @Synchronized
    operator fun get(path: String?): ImageBitmap? = path?.let { entries[it] }

    @Synchronized
    operator fun set(path: String, bitmap: ImageBitmap) {
        if (entries.size >= MAX_ENTRIES) {
            entries.keys.firstOrNull()?.let { entries.remove(it) }
        }
        entries[path] = bitmap
    }
}

private fun decodeAsset(context: Context, path: String, targetWidthPx: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidthPx)
    }
    val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
    bitmap?.asImageBitmap()
} catch (e: Exception) {
    null
}

private fun calculateInSampleSize(width: Int, height: Int, targetWidth: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= targetWidth && h / 2 >= targetWidth) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

/**
 * Builds the numbered pin used on the itinerary map.
 *
 * A number on the pin is what lets someone match "stop 7" in the list to a dot on the map at a
 * glance, which a generic teardrop marker cannot do.
 */
fun numberedMarkerIcon(
    context: Context,
    number: Int,
    fillColor: Int,
    visited: Boolean,
): Drawable {
    // The badge is anchored by its bottom edge, so transparent padding below the circle becomes a
    // silent northward offset on the map: the previous 8 px inset put every stop roughly 7 m from
    // the place it names at zoom 17. Sizing the circle to the bitmap removes it, so the bottom edge
    // of the ring sits exactly on the coordinate.
    // 110 -> 80, with the ring and the number scaled by the same factor (7 -> 5, 52 -> 38) so the
    // proportions are unchanged: the badge is smaller, not thinner or more crowded. The ring still
    // meets the bitmap edge, which is what keeps the bottom of the badge on its coordinate.
    val size = 80
    val strokeWidth = 5f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f - strokeWidth / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = fillColor
    canvas.drawCircle(size / 2f, size / 2f, radius, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = strokeWidth
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, radius, paint)

    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 38f
    paint.textAlign = Paint.Align.CENTER
    paint.isFakeBoldText = true
    val label = if (visited) "✓" else number.toString()
    val centerY = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(label, size / 2f, centerY, paint)

    return BitmapDrawable(context.resources, bitmap)
}
