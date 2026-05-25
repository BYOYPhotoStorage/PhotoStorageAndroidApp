package com.hriyaan.photostorage.dedup

import android.content.Context
import android.net.Uri
import com.hriyaan.photostorage.data.FileLogger
import com.hriyaan.photostorage.data.MediaStorePhoto
import com.hriyaan.photostorage.data.UploadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

sealed class DuplicateResult {
    object NotDuplicate : DuplicateResult()
    data class Duplicate(val reason: String) : DuplicateResult()
}

class DuplicateDetector(
    private val context: Context,
    private val uploadDao: UploadDao
) {

    suspend fun isDuplicate(photo: MediaStorePhoto): DuplicateResult {
        val logger = FileLogger.getInstance(context)
        val existing = uploadDao.findByFilenameAndSize(photo.filename, photo.size)
        if (existing != null && existing.dateTaken == photo.dateTakenMs) {
            logger.d(TAG, "isDuplicate=true | reason=filename_size_date filename=${photo.filename} size=${photo.size}")
            return DuplicateResult.Duplicate("filename_size_date")
        }

        val hash = computeSha256(photo.uri)
        val existingByHash = uploadDao.findBySha256(hash)
        if (existingByHash != null) {
            logger.d(TAG, "isDuplicate=true | reason=sha256 filename=${photo.filename} hash=${hash.take(16)}...")
            return DuplicateResult.Duplicate("sha256")
        }

        logger.d(TAG, "isDuplicate=false | filename=${photo.filename} size=${photo.size}")
        return DuplicateResult.NotDuplicate
    }

    suspend fun computeSha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            } ?: return@withContext ""
        } catch (_: IOException) {
            return@withContext ""
        } catch (_: SecurityException) {
            return@withContext ""
        }

        digest.digest().toHex()
    }

    companion object {
        private const val TAG = "DuplicateDetector"
    }

    private fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            result.append(hexChars[v ushr 4])
            result.append(hexChars[v and 0x0F])
        }
        return result.toString()
    }
}
