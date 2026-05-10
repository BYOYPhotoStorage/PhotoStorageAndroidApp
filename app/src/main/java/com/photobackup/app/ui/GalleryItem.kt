package com.photobackup.app.ui

import com.photobackup.app.data.MediaStorePhoto
import com.photobackup.app.data.UploadRecord

data class GalleryItem(
    val photo: MediaStorePhoto,
    val record: UploadRecord?
)
