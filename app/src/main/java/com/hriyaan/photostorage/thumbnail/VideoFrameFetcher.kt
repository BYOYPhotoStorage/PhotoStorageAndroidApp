package com.hriyaan.photostorage.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class VideoFrameFetcher(
    private val context: Context,
    private val uri: Uri
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val retriever = MediaMetadataRetriever()
        val frame: Bitmap = try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("No frame for $uri")
        } finally {
            runCatching { retriever.release() }
        }
        val bytes = ByteArrayOutputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        if (!frame.isRecycled) frame.recycle()
        return SourceResult(
            source = ImageSource(ByteArrayInputStream(bytes).source().buffer(), context),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            return if (isVideoUri(context, data)) VideoFrameFetcher(context, data) else null
        }

        private fun isVideoUri(context: Context, uri: Uri): Boolean {
            val type = try {
                context.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            return type?.startsWith("video/") == true
        }
    }
}
