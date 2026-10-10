package com.walkingtours.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
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
import kotlin.math.roundToInt

/**
 * Decodes a bundled asset photograph without pulling in an image-loading library.
 *
 * The images ship inside the APK, so there is no network, no cache and no cancellation problem to
 * solve — a straight background decode into an [ImageBitmap] is enough. Decoding is aimed at the
 * width the photograph will be displayed at (see [decodeAsset]): the source images are wider than
 * any card that shows them, and decoding one at full size costs megabytes of heap and a visible
 * upload on the frame it first appears.
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeExact(context, path, targetWidthPx)
    } else {
        decodeHalved(context, path, targetWidthPx)
    }
} catch (e: Exception) {
    null
}

/**
 * Android 9 and later: scale while decoding.
 *
 * [ImageDecoder] resizes as it decodes, so a 1600 px JPEG that will be shown in a phone-width card
 * is never materialised at full size — roughly a quarter of the heap and of the upload the GPU does
 * the first time the image appears. It also honours the photograph's EXIF orientation, which
 * [BitmapFactory] ignores.
 *
 * Why not [BitmapFactory] everywhere: its sampler can only halve. For these photographs the first
 * halving already lands under the width the cards display at (1600 -> 800 for a 1080 px card), so
 * it refuses to halve at all and hands back the full-size bitmap.
 */
private fun decodeExact(context: Context, path: String, targetWidthPx: Int): ImageBitmap {
    val source = ImageDecoder.createSource(context.assets, path)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        // Software, exactly like the fallback path: one memory model for the cache and the UI.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        if (width > targetWidthPx) {
            val height = (info.size.height * (targetWidthPx.toFloat() / width)).roundToInt()
            decoder.setTargetSize(targetWidthPx, height.coerceAtLeast(1))
        }
    }
    return bitmap.asImageBitmap()
}

/**
 * The pre-Android-9 path: [BitmapFactory] with power-of-two downsampling, as the app always did.
 * Kept for the two oldest supported versions (the app's own floor is API 26) rather than dropped.
 */
private fun decodeHalved(context: Context, path: String, targetWidthPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidthPx)
    }
    val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
    return bitmap?.asImageBitmap()
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
 * The pins already drawn, keyed by what one is made of.
 *
 * A map builds one of these per stop every time it appears: a bitmap with a circle, a ring and a
 * number in it, on the main thread, as the screen is arriving. Fifteen of them for this tour, and
 * the same fifteen the next time a map is put on screen, so they are kept. A fourteen-stop tour
 * costs a few hundred kilobytes for it.
 *
 * The colour is part of the key because it carries the state — blue for a stop still to come, teal
 * for one already walked, amber for the selected one — and so is [visited], which replaces the
 * number with a tick.
 *
 * Bitmaps rather than drawables: a drawable holds on to the Context it was built from, and these
 * outlive the screen that asked for them.
 */
private val markerBitmaps = mutableMapOf<Triple<Int, Int, Boolean>, Bitmap>()

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
): Drawable = BitmapDrawable(
    context.resources,
    markerBitmaps.getOrPut(Triple(number, fillColor, visited)) {
        drawMarkerIcon(number, fillColor, visited)
    },
)

private fun drawMarkerIcon(number: Int, fillColor: Int, visited: Boolean): Bitmap {
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

    return bitmap
}
