package com.photobackup.app.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.IOException

class ThumbnailGen(private val context: Context) {

    /**
     * Decodes [uri] and returns a square WebP-compressed thumbnail no larger than
     * [maxDim] x [maxDim]. Output is typically 20–40 KB.
     *
     * @throws IOException if the source URI cannot be opened or decoded.
     */
    fun createWebPThumbnail(uri: Uri, maxDim: Int = 200): ByteArray {
        val bitmap = try {
            context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
        } catch (_: Exception) {
            decodeSampled(uri, maxDim)
        }
        return try {
            ByteArrayOutputStream().use { out ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
                out.toByteArray()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun decodeSampled(uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Could not decode bounds for $uri")
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inJustDecodeBounds = false
        }
        val sampled = context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: throw IOException("Could not decode $uri")

        val scale = maxDim.toFloat() / maxOf(sampled.width, sampled.height)
        if (scale >= 1f) return sampled
        val w = (sampled.width * scale).toInt().coerceAtLeast(1)
        val h = (sampled.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(sampled, w, h, true)
        if (scaled !== sampled) sampled.recycle()
        return scaled
    }

    private fun calculateInSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
