package com.hriyaan.photostorage.share

import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.ShareLinkDao
import com.hriyaan.photostorage.data.ShareLinkRecord
import com.hriyaan.photostorage.gallery.GalleryItem

class ShareLinkService(
    private val s3Uploader: S3Uploader,
    private val shareLinkDao: ShareLinkDao
) {

    suspend fun createLink(item: GalleryItem, ttl: ShareLinkTtl): Result<ShareLinkRecord> {
        val (uploadId, b2Path) = when (item) {
            is GalleryItem.CloudOnly ->
                item.uploadRecord.id to (item.uploadRecord.photoB2Path
                    ?: return Result.failure(IllegalStateException("No B2 path")))

            is GalleryItem.Synced ->
                item.uploadRecord.id to (item.uploadRecord.photoB2Path
                    ?: return Result.failure(IllegalStateException("No B2 path")))

            is GalleryItem.LocalOnly ->
                return Result.failure(IllegalStateException("Not cloud-backed"))
        }

        val url = s3Uploader.presignGetUrl(b2Path, ttl.seconds).getOrElse {
            return Result.failure(it)
        }

        val now = System.currentTimeMillis()
        val record = ShareLinkRecord(
            uploadId = uploadId,
            photoB2Path = b2Path,
            url = url,
            createdAt = now,
            expiresAt = ttl.expiryFromNow(now)
        )

        val newId = shareLinkDao.insert(record)
        return Result.success(record.copy(id = newId))
    }

    suspend fun activeLinks(now: Long = System.currentTimeMillis()): List<ShareLinkRecord> {
        return shareLinkDao.getActive(now)
    }
}
