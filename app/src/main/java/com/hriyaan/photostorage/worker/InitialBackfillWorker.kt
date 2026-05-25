package com.hriyaan.photostorage.worker

import android.content.Context
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.data.FileLogger
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
        val logger = FileLogger.getInstance(applicationContext)

        val since = inputData.getLong(KEY_SINCE, 0L)
        val sinceValue = if (since <= 0L) null else since
        val selectedBuckets = prefs.getSelectedBucketIds()

        logger.i(TAG, "doWork start | since=${sinceValue ?: "null"} bucketCount=${selectedBuckets.size} buckets=${selectedBuckets.joinToString(",")}")

        val photos = scanner.scanImages(
            since = sinceValue,
            dateColumn = MediaStore.Images.Media.DATE_TAKEN,
            bucketIds = selectedBuckets
        )
        logger.i(TAG, "scanImages complete | count=${photos.size}")

        var enqueuedCount = 0
        photos.forEach {
            if (enqueueIfNew(it, dao)) enqueuedCount++
        }

        var videoCount = 0
        var videoEnqueued = 0
        if (prefs.getVideosEnabled()) {
            val videos = scanner.scanVideos(
                since = sinceValue,
                dateColumn = MediaStore.Video.Media.DATE_TAKEN,
                bucketIds = selectedBuckets
            )
            videoCount = videos.size
            videos.forEach {
                if (enqueueIfNew(it, dao)) videoEnqueued++
            }
        }
        logger.i(TAG, "scanVideos complete | count=$videoCount enqueued=$videoEnqueued")

        prefs.setLastScanTimestamp(System.currentTimeMillis())
        logger.i(TAG, "doWork success | photosEnqueued=$enqueuedCount videosEnqueued=$videoEnqueued total=${enqueuedCount + videoEnqueued}")
        Result.success()
    }

    private fun enqueueIfNew(item: MediaItem, dao: UploadDao): Boolean {
        val existing = dao.findByFilenameAndSize(item.filename, item.size)
        if (existing != null) {
            return false
        }

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
                mediaType = item.mediaType.toDbValue(),
                bucketId = item.bucketId
            )
        )
        return true
    }

    companion object {
        private const val TAG = "InitialBackfillWorker"
        const val KEY_SINCE = "since"
    }
}
