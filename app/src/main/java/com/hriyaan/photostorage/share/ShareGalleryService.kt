package com.hriyaan.photostorage.share

import aws.smithy.kotlin.runtime.content.ByteStream
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.ShareGalleryDao
import com.hriyaan.photostorage.data.ShareGalleryRecord
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.gallery.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ShareGalleryService(
    private val s3Uploader: S3Uploader,
    private val shareGalleryDao: ShareGalleryDao,
    private val uploadDao: UploadDao,
    private val htmlGenerator: ShareGalleryHtmlGenerator
) {

    suspend fun createGalleryLink(items: List<GalleryItem>, ttl: ShareLinkTtl): Result<ShareGalleryRecord> =
        withContext(Dispatchers.IO) {
            val eligibleItems = items.filter { it !is GalleryItem.LocalOnly }
            if (eligibleItems.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No cloud-backed items selected"))
            }

            val shareItems = mutableListOf<GalleryShareItem>()
            for (item in eligibleItems) {
                val record = when (item) {
                    is GalleryItem.CloudOnly -> item.uploadRecord
                    is GalleryItem.Synced -> item.uploadRecord
                    is GalleryItem.LocalOnly -> continue
                }

                val photoPath = record.photoB2Path ?: continue
                val thumbnailPath = record.thumbnailB2Path ?: continue
                val mediaType = record.mediaType

                val fullUrl = s3Uploader.presignGetUrl(photoPath, ttl.seconds).getOrElse {
                    return@withContext Result.failure(it)
                }
                val thumbUrl = s3Uploader.presignGetUrl(thumbnailPath, ttl.seconds).getOrElse {
                    return@withContext Result.failure(it)
                }

                shareItems += GalleryShareItem(
                    filename = record.filename,
                    mediaType = mediaType,
                    thumbnailUrl = thumbUrl,
                    fullUrl = fullUrl
                )
            }

            if (shareItems.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No items could be shared"))
            }

            val html = htmlGenerator.generate(shareItems)
            val htmlBytes = html.toByteArray(Charsets.UTF_8)
            val htmlKey = "shares/gallery-${UUID.randomUUID()}.html"

            s3Uploader.upload(
                key = htmlKey,
                contentType = "text/html",
                contentLength = htmlBytes.size.toLong(),
                body = ByteStream.fromBytes(htmlBytes)
            ).getOrElse {
                return@withContext Result.failure(it)
            }

            val htmlUrl = s3Uploader.presignGetUrl(htmlKey, ttl.seconds).getOrElse {
                return@withContext Result.failure(it)
            }

            val now = System.currentTimeMillis()
            val record = ShareGalleryRecord(
                htmlB2Path = htmlKey,
                url = htmlUrl,
                itemCount = shareItems.size,
                createdAt = now,
                expiresAt = ttl.expiryFromNow(now)
            )

            val newId = shareGalleryDao.insert(record)
            Result.success(record.copy(id = newId))
        }

    suspend fun activeGalleries(now: Long = System.currentTimeMillis()): List<ShareGalleryRecord> {
        return shareGalleryDao.getActive(now)
    }

    suspend fun deleteExpiredGalleries(now: Long = System.currentTimeMillis()): Int {
        val expired = shareGalleryDao.getOlderThan(now)
        for (record in expired) {
            s3Uploader.deleteObject(record.htmlB2Path)
        }
        return shareGalleryDao.deleteOlderThan(now)
    }
}
