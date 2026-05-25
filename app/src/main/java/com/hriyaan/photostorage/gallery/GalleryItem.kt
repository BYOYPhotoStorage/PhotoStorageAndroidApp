package com.hriyaan.photostorage.gallery

import android.net.Uri
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord

sealed class GalleryItem {
    abstract val id: String
    abstract val dateTaken: Long
    abstract val filename: String
    abstract val thumbnailSource: ThumbnailSource

    data class LocalOnly(
        override val id: String,
        override val dateTaken: Long,
        override val filename: String,
        override val thumbnailSource: ThumbnailSource,
        val mediaStoreUri: Uri,
        val sizeBytes: Long,
        val queuedRecord: UploadRecord?,
        val mediaType: String = UploadDao.MEDIA_TYPE_PHOTO,
        val bucketId: String? = null
    ) : GalleryItem()

    data class CloudOnly(
        override val id: String,
        override val dateTaken: Long,
        override val filename: String,
        override val thumbnailSource: ThumbnailSource,
        val uploadRecord: UploadRecord
    ) : GalleryItem()

    data class Synced(
        override val id: String,
        override val dateTaken: Long,
        override val filename: String,
        override val thumbnailSource: ThumbnailSource,
        val mediaStoreUri: Uri,
        val uploadRecord: UploadRecord
    ) : GalleryItem()
}

sealed class ThumbnailSource {
    data class LocalUri(val uri: Uri) : ThumbnailSource()
    data class B2Path(val path: String) : ThumbnailSource()
}
