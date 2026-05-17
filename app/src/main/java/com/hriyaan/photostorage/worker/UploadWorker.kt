package com.hriyaan.photostorage.worker

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.content.ByteStream
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.notification.NotificationChannels
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.thumbnail.ThumbnailGen
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
        if (prefsStore.isWifiOnly() && isOnMeteredNetwork()) {
            val pendingCount = uploadDao.getPendingQueue().size + uploadDao.getFailedRetryable().size
            val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_SERVICE)
                .setContentTitle("Photo backup is active")
                .setContentText("Waiting for Wi-Fi — $pendingCount pending")
                .setSmallIcon(R.drawable.ic_notification_upload)
                .setOngoing(true)
                .setSilent(true)
                .build()
            NotificationManagerCompat.from(context)
                .notify(UploadNotificationManager.ID_FOREGROUND, notification)
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
            val errorCode = e.sdkErrorMetadata.errorCode
            if (errorCode in AUTH_ERROR_CODES) {
                uploadDao.updateStatus(record.id, UploadDao.STATUS_PERMANENTLY_FAILED)
                galleryRepository.invalidate()
                notificationManager.showAuthFailureNotification()
                Result.failure(e)
            } else {
                markFailed(record)
                Result.failure(e)
            }
        } catch (e: Exception) {
            markFailed(record)
            Result.failure(e)
        }
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

    private fun isOnMeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    companion object {
        private val AUTH_ERROR_CODES = setOf(
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "AccessDenied"
        )
    }
}
