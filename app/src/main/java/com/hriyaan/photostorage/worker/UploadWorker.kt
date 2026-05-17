package com.hriyaan.photostorage.worker

import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.thumbnail.ThumbnailGen
import android.content.Context

class UploadWorker(
    private val context: Context,
    private val uploadDao: UploadDao,
    private val s3Uploader: S3Uploader?,
    private val thumbnailGen: ThumbnailGen,
    private val notificationManager: UploadNotificationManager
) {

    suspend fun processQueue() {
        // TODO: Implemented in Task 14
    }

    suspend fun processSingle(record: UploadRecord): Result<Unit> {
        // TODO: Implemented in Task 14
        return Result.success(Unit)
    }
}
