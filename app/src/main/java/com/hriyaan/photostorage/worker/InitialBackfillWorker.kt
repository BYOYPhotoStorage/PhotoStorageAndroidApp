package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.media.MediaItem
import com.hriyaan.photostorage.media.MediaStoreScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InitialBackfillWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PhotoBackupApp
        val prefs = app.prefsStore
        val dao = app.uploadDatabase.dao
        val scanner = MediaStoreScanner(applicationContext)

        val photos = scanner.scanImages(since = null)
        photos.forEach { enqueueIfNew(it, dao) }

        if (prefs.getVideosEnabled()) {
            val videos = scanner.scanVideos(since = null)
            videos.forEach { enqueueIfNew(it, dao) }
        }

        prefs.setLastScanTimestamp(System.currentTimeMillis())
        Result.success()
    }

    private fun enqueueIfNew(item: MediaItem, dao: UploadDao) {
        val existing = dao.findByFilenameAndSize(item.filename, item.size)
        if (existing != null) return

        val photoPath = S3KeyBuilder.photoKey(item.filename, item.dateTaken)
        val thumbPath = S3KeyBuilder.thumbnailKey(item.filename, item.dateTaken)

        dao.insert(
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
    }
}
