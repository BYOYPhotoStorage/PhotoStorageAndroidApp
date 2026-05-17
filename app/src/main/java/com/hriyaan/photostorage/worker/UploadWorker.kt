package com.hriyaan.photostorage.worker

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.fromFile
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.media.Transcoder
import com.hriyaan.photostorage.notification.NotificationChannels
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.thumbnail.ThumbnailGen
import java.io.File
import java.io.IOException
import java.util.Calendar
import kotlin.math.pow

@SuppressLint("MissingPermission", "NotificationPermission")
class UploadWorker(
    private val context: Context,
    private val uploadDao: UploadDao,
    private val s3Uploader: S3Uploader,
    private val thumbnailGen: ThumbnailGen,
    private val notificationManager: UploadNotificationManager
) {

    private val prefsStore = PrefsStore(context)
    private val galleryRepository = (context.applicationContext as PhotoBackupApp).galleryRepository

    suspend fun processQueue() {
        val now = System.currentTimeMillis()
        val mode = prefsStore.getUploadMode()
        val gate = UploadModeGate(context, prefsStore)
        val decision = if (mode == UploadModeGate.MODE_SCHEDULED && isInNightlyWindow(now)) {
            UploadModeGate.Decision.UploadNow
        } else {
            gate.decide(now)
        }

        if (decision is UploadModeGate.Decision.Defer) {
            val pendingCount = uploadDao.getPendingQueue().size + uploadDao.getFailedRetryable().size
            showDeferredNotification(decision.reason, pendingCount)
            return
        }

        val pending = uploadDao.getPendingQueue()
        val retryable = uploadDao.getFailedRetryable()
        val queue = (pending + retryable).sortedBy { it.createdAt }

        if (queue.isEmpty()) {
            notificationManager.cancelProgressNotification()
            val notification = notificationManager.buildForegroundNotification(0)
            NotificationManagerCompat.from(context)
                .notify(UploadNotificationManager.ID_FOREGROUND, notification)
            return
        }

        val total = queue.size
        var successCount = 0
        var permanentFailureCount = 0

        notificationManager.showProgressNotification(0, total)

        for ((index, record) in queue.withIndex()) {
            val current = index + 1
            val remaining = total - current + 1
            val foregroundNotification = notificationManager.buildForegroundNotification(remaining)
            NotificationManagerCompat.from(context)
                .notify(UploadNotificationManager.ID_FOREGROUND, foregroundNotification)
            notificationManager.showProgressNotification(current, total)

            uploadDao.updateStatus(record.id, UploadDao.STATUS_UPLOADING)
            galleryRepository.invalidate()

            val wasAlreadyPermanent = record.status == UploadDao.STATUS_PERMANENTLY_FAILED
            val willBecomePermanent = record.retryCount >= 4

            val result = processSingle(record)
            if (result.isSuccess) {
                successCount++
            } else if (!wasAlreadyPermanent) {
                val ex = result.exceptionOrNull()
                val isAuthFailure = ex is S3Exception &&
                    ex.sdkErrorMetadata.errorCode in AUTH_ERROR_CODES
                if (isAuthFailure || willBecomePermanent) {
                    permanentFailureCount++
                }
            }
        }

        if (successCount > 0) {
            notificationManager.showCompletionNotification(successCount)
        }
        if (permanentFailureCount > 0) {
            notificationManager.showPermanentFailureNotification(permanentFailureCount)
        }
        notificationManager.cancelProgressNotification()

        val remaining = uploadDao.getPendingQueue().size + uploadDao.getFailedRetryable().size
        val finalNotification = notificationManager.buildForegroundNotification(remaining)
        NotificationManagerCompat.from(context)
            .notify(UploadNotificationManager.ID_FOREGROUND, finalNotification)
    }

    suspend fun processSingle(record: UploadRecord): Result<Unit> {
        return when (record.mediaType) {
            UploadDao.MEDIA_TYPE_VIDEO -> processVideoRecord(record)
            else -> processPhotoRecord(record)
        }
    }

    private suspend fun processPhotoRecord(record: UploadRecord): Result<Unit> {
        val uri = try {
            Uri.parse(record.localUri)
        } catch (e: Exception) {
            markFailed(record)
            return Result.failure(e)
        }

        val inputStream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            markFailed(record)
            return Result.failure(e)
        } catch (e: Exception) {
            markFailed(record)
            return Result.failure(e)
        } ?: run {
            val e = IllegalStateException("Could not open input stream for $uri")
            markFailed(record)
            return Result.failure(e)
        }

        val photoBytes = try {
            inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            markFailed(record)
            return Result.failure(e)
        }

        val photoKey = record.photoB2Path ?: S3KeyBuilder.photoKey(record.filename, record.dateTaken)
        val thumbKey = record.thumbnailB2Path ?: S3KeyBuilder.thumbnailKey(record.filename, record.dateTaken)

        return try {
            s3Uploader.upload(
                key = photoKey,
                contentType = "image/jpeg",
                contentLength = photoBytes.size.toLong(),
                body = ByteStream.fromBytes(photoBytes)
            ).getOrThrow()

            val thumbBytes = thumbnailGen.createWebPThumbnail(uri)
            s3Uploader.upload(
                key = thumbKey,
                contentType = "image/webp",
                contentLength = thumbBytes.size.toLong(),
                body = ByteStream.fromBytes(thumbBytes)
            ).getOrThrow()

            uploadDao.setUploadedPaths(record.id, photoKey, thumbKey, System.currentTimeMillis())
            galleryRepository.invalidate()
            Result.success(Unit)
        } catch (e: S3Exception) {
            handleS3Exception(record, e)
        } catch (e: Exception) {
            markFailed(record)
            Result.failure(e)
        }
    }

    private suspend fun processVideoRecord(record: UploadRecord): Result<Unit> {
        val uri = try {
            Uri.parse(record.localUri)
        } catch (e: Exception) {
            markFailed(record)
            return Result.failure(e)
        }

        val qualityMode = prefsStore.getVideoQualityMode()
        val thresholdMin = prefsStore.getVideoDurationThresholdMinutes()
        val shouldCompress = when (qualityMode) {
            QUALITY_COMPRESSED -> true
            QUALITY_DURATION_BASED -> {
                val durationMs = extractVideoDurationMs(uri) ?: Long.MAX_VALUE
                durationMs > thresholdMin * 60_000L
            }
            else -> false
        }

        val thumbKey = S3KeyBuilder.thumbnailKey(record.filename, record.dateTaken)
        val originalKey = S3KeyBuilder.videoKey(record.filename, record.dateTaken)
        val compressedKey = S3KeyBuilder.compressedVideoKey(record.filename, record.dateTaken)

        var transcodedFile: File? = null
        var tempUploadFile: File? = null
        var actuallyCompressed = false
        var uploadedKey = originalKey

        return try {
            if (shouldCompress) {
                val outputFile = File(
                    context.cacheDir,
                    "transcoded/${record.filename}.compressed.mp4"
                ).apply { parentFile?.mkdirs() }
                val targetRes = prefsStore.getVideoTargetResolution()
                val transcodeResult = Transcoder(context).transcode(uri, outputFile, targetRes)
                if (transcodeResult.isSuccess) {
                    transcodedFile = outputFile
                    actuallyCompressed = true
                    uploadedKey = compressedKey
                } else {
                    Log.w(
                        TAG,
                        "Transcode failed for ${record.filename}: ${transcodeResult.exceptionOrNull()?.message}"
                    )
                }
            }

            val uploadFile: File = transcodedFile ?: run {
                val temp = copyUriToTempFile(uri, record.filename)
                tempUploadFile = temp
                temp
            }

            s3Uploader.upload(
                key = uploadedKey,
                contentType = if (actuallyCompressed) "video/mp4" else videoContentType(record.filename),
                contentLength = uploadFile.length(),
                body = ByteStream.fromFile(uploadFile)
            ).getOrThrow()

            val thumbBytes = thumbnailGen.createWebPThumbnailFromVideo(uri)
            s3Uploader.upload(
                key = thumbKey,
                contentType = "image/webp",
                contentLength = thumbBytes.size.toLong(),
                body = ByteStream.fromBytes(thumbBytes)
            ).getOrThrow()

            uploadDao.setUploadedVideoPaths(
                id = record.id,
                videoPath = uploadedKey,
                thumbnailPath = thumbKey,
                uploadedAt = System.currentTimeMillis(),
                compressed = actuallyCompressed,
                originalPathB2 = null
            )
            galleryRepository.invalidate()
            Result.success(Unit)
        } catch (e: S3Exception) {
            handleS3Exception(record, e)
        } catch (e: Exception) {
            markFailed(record)
            Result.failure(e)
        } finally {
            runCatching { transcodedFile?.delete() }
            runCatching { tempUploadFile?.delete() }
        }
    }

    private fun handleS3Exception(record: UploadRecord, e: S3Exception): Result<Unit> {
        val errorCode = e.sdkErrorMetadata.errorCode
        return if (errorCode in AUTH_ERROR_CODES) {
            uploadDao.updateStatus(record.id, UploadDao.STATUS_PERMANENTLY_FAILED)
            galleryRepository.invalidate()
            notificationManager.showAuthFailureNotification()
            Result.failure(e)
        } else {
            markFailed(record)
            Result.failure(e)
        }
    }

    private fun extractVideoDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun copyUriToTempFile(uri: Uri, filename: String): File {
        val tempFile = File(context.cacheDir, "uploads/$filename").apply {
            parentFile?.mkdirs()
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Could not open input stream for $uri")
        return tempFile
    }

    private fun videoContentType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    private fun isInNightlyWindow(now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return cal.get(Calendar.HOUR_OF_DAY) in 2..3
    }

    private fun showDeferredNotification(reason: String, pendingCount: Int) {
        val text = if (pendingCount == 0) reason else "$reason — $pendingCount pending"
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_SERVICE)
            .setContentTitle("Photo backup is active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()
        NotificationManagerCompat.from(context)
            .notify(UploadNotificationManager.ID_FOREGROUND, notification)
    }

    private fun markFailed(record: UploadRecord) {
        val newRetryCount = record.retryCount + 1
        if (newRetryCount >= 5) {
            uploadDao.updateStatus(record.id, UploadDao.STATUS_PERMANENTLY_FAILED)
        } else {
            val nextRetry = System.currentTimeMillis() +
                (2.0.pow(newRetryCount).toLong() * 60 * 1000)
            uploadDao.updateRetry(record.id, newRetryCount, nextRetry)
            uploadDao.updateStatus(record.id, UploadDao.STATUS_FAILED)
        }
        galleryRepository.invalidate()
    }

    companion object {
        private const val TAG = "UploadWorker"

        private const val QUALITY_ORIGINAL = "original"
        private const val QUALITY_COMPRESSED = "compressed"
        private const val QUALITY_DURATION_BASED = "duration_based"

        private val AUTH_ERROR_CODES = setOf(
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "AccessDenied"
        )
    }
}
