package com.hriyaan.photostorage.data

import android.net.Uri

data class MediaStorePhoto(
    val id: Long,
    val uri: Uri,
    val filename: String,
    val size: Long,
    val dateTakenMs: Long,
    val mediaType: String = "photo",
    val bucketId: String? = null,
    val bucketName: String? = null
)

data class PhotoFolder(
    val bucketId: String,
    val bucketName: String,
    val itemCount: Int
)
