package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.MediaStorePhoto
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.dedup.DuplicateDetector
import com.hriyaan.photostorage.media.MediaItem
import com.hriyaan.photostorage.media.MediaStoreScanner
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.thumbnail.ThumbnailGen

class NightlyScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PhotoBackupApp
        val uploadDao = app.uploadDatabase.dao
        val prefsStore = app.prefsStore
        val scanner = MediaStoreScanner(applicationContext)
        val duplicateDetector = DuplicateDetector(applicationContext, uploadDao)

        val selectedBuckets = prefsStore.getSelectedBucketIds()
        val sinceFromInput = inputData.getLong(KEY_SINCE, -1L).takeIf { it >= 0L }
        val sinceArg = (sinceFromInput ?: prefsStore.getLastScanTimestamp()).takeIf { it > 0L }
        val items = scanner.scanImages(sinceArg, bucketIds = selectedBuckets) +
            scanner.scanVideos(sinceArg, bucketIds = selectedBuckets)

        var enqueuedCount = 0
        for (item in items) {
            val dupResult = duplicateDetector.isDuplicate(item.toMediaStorePhoto())
            if (dupResult is com.hriyaan.photostorage.dedup.DuplicateResult.Duplicate) {
                continue
            }

            val photoPath = S3KeyBuilder.photoKey(item.filename, item.dateTaken)
            val thumbPath = S3KeyBuilder.thumbnailKey(item.filename, item.dateTaken)

            uploadDao.insert(
                UploadRecord(
                    localUri = item.uri.toString(),
                    filename = item.filename,
                    size = item.size,
                    dateTaken = item.dateTaken,
                    photoB2Path = photoPath,
                    thumbnailB2Path = thumbPath,
                    status = UploadDao.STATUS_PENDING,
                    uploadedAt = null,
                    mediaType = item.mediaType.toDbValue()
                )
            )
            enqueuedCount++
        }

        prefsStore.setLastScanTimestamp(System.currentTimeMillis())

        if (enqueuedCount > 0) {
            val creds = prefsStore.getCredentials()
            if (creds != null) {
                val config = S3Config.forBucket(creds.bucketName)
                val client = S3ClientFactory.create(creds, config)
                val s3Uploader = S3Uploader(client, creds.bucketName)
                val worker = UploadWorker(
                    applicationContext,
                    uploadDao,
                    s3Uploader,
                    ThumbnailGen(applicationContext),
                    UploadNotificationManager(applicationContext)
                )
                worker.processQueue()
            }
        }

        return Result.success()
    }

    private fun MediaItem.toMediaStorePhoto(): MediaStorePhoto = MediaStorePhoto(
        id = 0L,
        uri = uri,
        filename = filename,
        size = size,
        dateTakenMs = dateTaken
    )

    companion object {
        const val KEY_SINCE = "since"
    }
}
