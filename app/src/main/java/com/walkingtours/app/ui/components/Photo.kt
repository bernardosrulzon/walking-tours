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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
    val bitmap by produceState<ImageBitmap?>(initialValue = null, assetPath) {
        value = assetPath?.let {
            withContext(Dispatchers.IO) { decodeAsset(context, it, targetWidthPx) }
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
            Text(
                text = "Photo unavailable",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    val size = 110
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f - 8f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = fillColor
    canvas.drawCircle(size / 2f, size / 2f, radius, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 7f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, radius, paint)

    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 52f
    paint.textAlign = Paint.Align.CENTER
    paint.isFakeBoldText = true
    val label = if (visited) "✓" else number.toString()
    val centerY = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(label, size / 2f, centerY, paint)

    return BitmapDrawable(context.resources, bitmap)
}
