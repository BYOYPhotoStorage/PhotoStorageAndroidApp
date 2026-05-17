package com.hriyaan.photostorage.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
        return encodeWebP(bitmap)
    }

    /**
     * Extracts the first frame of a video at [uri] and returns it as a WebP-compressed
     * thumbnail no larger than [maxDim] x [maxDim].
     *
     * @throws IOException if the URI cannot be opened or no frame is available.
     */
    fun createWebPThumbnailFromVideo(uri: Uri, maxDim: Int = 200): ByteArray {
        val retriever = MediaMetadataRetriever()
        val frame: Bitmap = try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("No frame at time 0 for $uri")
        } finally {
            runCatching { retriever.release() }
        }
        val scaled = scaleToMaxDim(frame, maxDim)
        return encodeWebP(scaled)
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

        return scaleToMaxDim(sampled, maxDim)
    }

    private fun scaleToMaxDim(source: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / maxOf(source.width, source.height)
        if (scale >= 1f) return source
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun encodeWebP(bitmap: Bitmap): ByteArray {
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
