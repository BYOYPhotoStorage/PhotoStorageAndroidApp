package com.hriyaan.photostorage.thumbnail

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.gallery.ThumbnailSource
import java.io.File

class ThumbnailCacheFactory(
    private val context: Context,
    private val s3Uploader: S3Uploader,
    private val prefsStore: PrefsStore,
    private val egressRecorder: ((Long) -> Unit)? = null
) {
    private val imageLoader: ImageLoader by lazy { build() }

    fun get(): ImageLoader = imageLoader

    fun prefetch(paths: List<ThumbnailSource.B2Path>) {
        if (!shouldPrefetch()) return
        for (p in paths) {
            val request = ImageRequest.Builder(context)
                .data(p)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            imageLoader.enqueue(request)
        }
    }

    private fun shouldPrefetch(): Boolean {
        if (!prefsStore.isWifiOnly()) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun build(): ImageLoader {
        val thumbnailCacheDir = File(context.cacheDir, "thumbnails").also { it.mkdirs() }
        return ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(thumbnailCacheDir)
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.20)
                    .build()
            }
            .components {
                add(VideoFrameFetcher.Factory(context))
                add(B2ThumbnailFetcher.Factory(context, s3Uploader, egressRecorder))
            }
            .build()
    }
}
