package com.hriyaan.photostorage.media

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val filename: String,
    val size: Long,
    val dateTaken: Long,
    val mediaType: MediaType,
    val durationMs: Long?,
    val bucketId: String? = null
)
