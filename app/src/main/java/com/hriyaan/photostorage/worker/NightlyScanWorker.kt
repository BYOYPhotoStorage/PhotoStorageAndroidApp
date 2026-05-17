package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.MediaStoreQuery
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.dedup.DuplicateDetector
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.thumbnail.ThumbnailGen

class NightlyScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PhotoBackupApp
        val uploadDao = app.uploadDatabase.dao
        val prefsStore = app.prefsStore
        val mediaStoreQuery = MediaStoreQuery(applicationContext)
        val duplicateDetector = DuplicateDetector(applicationContext, uploadDao)

        val lastScan = prefsStore.getLastScanTimestamp()
        val photos = mediaStoreQuery.queryAllPhotos()

        var enqueuedCount = 0
        for (photo in photos) {
            val dupResult = duplicateDetector.isDuplicate(photo)
            if (dupResult is com.hriyaan.photostorage.dedup.DuplicateResult.Duplicate) {
                continue
            }

            val photoPath = S3KeyBuilder.photoKey(photo.filename, photo.dateTakenMs)
            val thumbPath = S3KeyBuilder.thumbnailKey(photo.filename, photo.dateTakenMs)

            uploadDao.insert(
                UploadRecord(
                    localUri = photo.uri.toString(),
                    filename = photo.filename,
                    size = photo.size,
                    dateTaken = photo.dateTakenMs,
                    photoB2Path = photoPath,
                    thumbnailB2Path = thumbPath,
                    status = UploadDao.STATUS_PENDING,
                    uploadedAt = null
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
}
