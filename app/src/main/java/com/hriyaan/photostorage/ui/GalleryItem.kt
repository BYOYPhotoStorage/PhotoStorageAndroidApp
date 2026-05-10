package com.hriyaan.photostorage.ui

import com.hriyaan.photostorage.data.MediaStorePhoto
import com.hriyaan.photostorage.data.UploadRecord

data class GalleryItem(
    val photo: MediaStorePhoto,
    val record: UploadRecord?
)
