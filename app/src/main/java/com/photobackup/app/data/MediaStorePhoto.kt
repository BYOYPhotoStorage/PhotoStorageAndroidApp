package com.photobackup.app.data

import android.net.Uri

data class MediaStorePhoto(
    val id: Long,
    val uri: Uri,
    val filename: String,
    val size: Long,
    val dateTakenMs: Long
)
