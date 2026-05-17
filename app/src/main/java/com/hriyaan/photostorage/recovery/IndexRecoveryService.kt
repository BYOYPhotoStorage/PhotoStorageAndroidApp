package com.hriyaan.photostorage.recovery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadDatabase
import com.hriyaan.photostorage.worker.IndexSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RemoteIndexInfo(val lastModified: Long?)
data class RestoreOutcome(val photoCount: Int, val latestUploadedAt: Long?)

class IndexRecoveryService(
    private val context: Context,
    private val s3Uploader: S3Uploader,
    private val uploadDao: UploadDao,
    private val uploadDatabase: UploadDatabase,
    private val prefsStore: PrefsStore
) {

    suspend fun hasRemoteIndex(): Result<RemoteIndexInfo?> = withContext(Dispatchers.IO) {
        s3Uploader.headObject(IndexSyncWorker.INDEX_B2_PATH).map { exists ->
            if (exists) RemoteIndexInfo(lastModified = null) else null
        }
    }

    suspend fun downloadAndRestore(
        @Suppress("UNUSED_PARAMETER") progress: (Float) -> Unit = {}
    ): Result<RestoreOutcome> = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore-${System.currentTimeMillis()}.sqlite")

        val downloadResult = s3Uploader.downloadObject(IndexSyncWorker.INDEX_B2_PATH, temp)
        if (downloadResult.isFailure) {
            runCatching { temp.delete() }
            return@withContext Result.failure(
                downloadResult.exceptionOrNull() ?: IllegalStateException("download failed")
            )
        }

        val validationError = validateRestoredFile(temp)
        if (validationError != null) {
            runCatching { temp.delete() }
            return@withContext Result.failure(validationError)
        }

        try {
            uploadDatabase.close()
            val dest = context.getDatabasePath(UploadDatabase.DB_NAME)
            dest.parentFile?.mkdirs()
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            return@withContext Result.failure(t)
        }

        prefsStore.setLastSyncedIndexHash(null)

        val photoCount = uploadDao.getCloudView().size
        val latestUploadedAt = uploadDao.getLatestUploadedAt()
        Result.success(RestoreOutcome(photoCount, latestUploadedAt))
    }

    suspend fun reconcileLocalPresent() = withContext(Dispatchers.IO) {
        val records = uploadDao.getCloudView()
        if (records.isEmpty()) return@withContext

        data class Key(val filename: String, val size: Long, val dateTaken: Long)
        val recordsByKey = records.associateBy { Key(it.filename, it.size, it.dateTaken) }

        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        ) ?: return@withContext

        val foundIds = mutableSetOf<Long>()
        cursor.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val filename = c.getString(nameIdx) ?: continue
                val size = c.getLong(sizeIdx)
                val dateTaken = if (c.isNull(dateIdx)) 0L else c.getLong(dateIdx)
                val key = Key(filename, size, dateTaken)
                val match = recordsByKey[key] ?: continue
                foundIds += match.id
            }
        }

        val recordIds = records.map { it.id }.toSet()
        val notFound = recordIds - foundIds
        for (id in foundIds) uploadDao.setLocalPresent(id, true)
        for (id in notFound) uploadDao.setLocalPresent(id, false)
    }

    private fun validateRestoredFile(file: File): Throwable? {
        return try {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                val hasUploads = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='uploads'",
                    null
                ).use { c -> c.moveToFirst() }
                if (!hasUploads) {
                    return IllegalStateException("Index is missing the uploads table")
                }
                val version = db.version
                if (version > UploadDatabase.DB_VERSION) {
                    return IndexTooNewException(version, UploadDatabase.DB_VERSION)
                }
            }
            null
        } catch (t: Throwable) {
            t
        }
    }

    class IndexTooNewException(val fileVersion: Int, val appVersion: Int) :
        IllegalStateException("Index schema $fileVersion is newer than app schema $appVersion")
}
