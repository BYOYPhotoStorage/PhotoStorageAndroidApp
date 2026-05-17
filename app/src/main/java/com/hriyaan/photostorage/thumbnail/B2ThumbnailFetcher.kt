package com.hriyaan.photostorage.thumbnail

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.gallery.ThumbnailSource
import okio.buffer
import okio.source
import java.io.File
import java.security.MessageDigest

class B2ThumbnailFetcher(
    private val path: String,
    private val context: Context,
    private val s3Uploader: S3Uploader,
    private val egressRecorder: ((Long) -> Unit)? = null
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val cacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
        val cacheFile = File(cacheDir, cacheKey(path))

        if (!cacheFile.exists()) {
            val tmp = File(cacheDir, "${cacheKey(path)}.tmp")
            try {
                s3Uploader.downloadObject(path, tmp).getOrThrow()
                egressRecorder?.invoke(tmp.length())
                if (!tmp.renameTo(cacheFile)) {
                    tmp.delete()
                    throw java.io.IOException("Could not finalize thumbnail cache entry for $path")
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

        cacheFile.setLastModified(System.currentTimeMillis())

        return SourceResult(
            source = ImageSource(cacheFile.source().buffer(), context),
            mimeType = "image/webp",
            dataSource = DataSource.DISK
        )
    }

    private fun cacheKey(p: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(p.toByteArray())
            .joinToString("") { "%02x".format(it) }

    class Factory(
        private val context: Context,
        private val s3Uploader: S3Uploader,
        private val egressRecorder: ((Long) -> Unit)? = null
    ) : Fetcher.Factory<ThumbnailSource.B2Path> {
        override fun create(
            data: ThumbnailSource.B2Path,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = B2ThumbnailFetcher(data.path, context, s3Uploader, egressRecorder)
    }
}
