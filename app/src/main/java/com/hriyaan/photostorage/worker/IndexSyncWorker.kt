package com.hriyaan.photostorage.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import aws.smithy.kotlin.runtime.content.ByteStream
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class IndexSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PhotoBackupApp
        val prefs = app.prefsStore
        val creds = prefs.getCredentials() ?: return@withContext Result.success()

        val src = applicationContext.getDatabasePath("uploads.db")
        if (!src.exists()) return@withContext Result.success()

        val temp = File(applicationContext.cacheDir, "index-${System.currentTimeMillis()}.sqlite")
        try {
            src.copyTo(temp, overwrite = true)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to copy uploads.db", t)
            temp.delete()
            return@withContext Result.retry()
        }

        var uploader: S3Uploader? = null
        try {
            val hash = sha256Hex(temp)

            if (hash == prefs.getLastSyncedIndexHash()) {
                Log.i(TAG, "Index unchanged, skipping upload")
                return@withContext Result.success()
            }

            val bytes = temp.readBytes()

            val config = S3Config.forBucket(creds.bucketName)
            val client = S3ClientFactory.create(creds, config)
            uploader = S3Uploader(client, creds.bucketName)
            val uploadResult = uploader.upload(
                key = INDEX_B2_PATH,
                contentType = "application/octet-stream",
                contentLength = bytes.size.toLong(),
                body = ByteStream.fromBytes(bytes)
            )
            if (uploadResult.isFailure) {
                Log.w(TAG, "Index upload failed", uploadResult.exceptionOrNull())
                return@withContext Result.retry()
            }

            Log.i(TAG, "Index uploaded to $INDEX_B2_PATH")
            prefs.setLastSyncedIndexHash(hash)
            prefs.setLastIndexSyncAt(System.currentTimeMillis())
            Result.success()
        } finally {
            uploader?.close()
            temp.delete()
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        const val INDEX_B2_PATH = "index/photo-storage-index.sqlite"
        private const val TAG = "IndexSyncWorker"
    }
}
