package com.hriyaan.photostorage.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_CREATED_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_DATE_TAKEN
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_FILENAME
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_ID
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_LOCAL_URI
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_NEXT_RETRY_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_PHOTO_B2_PATH
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_RETRY_COUNT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHA256
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SIZE
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_STATUS
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_THUMBNAIL_B2_PATH
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_UPLOADED_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.TABLE_UPLOADS

class UploadDao internal constructor(private val helper: SQLiteOpenHelper) {

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_UPLOADED = "uploaded"
        const val STATUS_FAILED = "failed"
        const val STATUS_PERMANENTLY_FAILED = "permanently_failed"

        private const val MAX_RETRIES = 5
    }

    fun insert(record: UploadRecord): Long {
        val values = ContentValues().apply {
            put(COL_LOCAL_URI, record.localUri)
            put(COL_FILENAME, record.filename)
            put(COL_SIZE, record.size)
            put(COL_DATE_TAKEN, record.dateTaken)
            put(COL_PHOTO_B2_PATH, record.photoB2Path)
            put(COL_THUMBNAIL_B2_PATH, record.thumbnailB2Path)
            put(COL_STATUS, record.status)
            record.uploadedAt?.let { put(COL_UPLOADED_AT, it) }
            put(COL_RETRY_COUNT, record.retryCount)
            record.nextRetryAt?.let { put(COL_NEXT_RETRY_AT, it) }
            record.sha256?.let { put(COL_SHA256, it) }
            put(COL_CREATED_AT, record.createdAt)
        }
        return helper.writableDatabase.insertOrThrow(TABLE_UPLOADS, null, values)
    }

    fun updateStatus(id: Long, status: String, uploadedAt: Long? = null): Int {
        val values = ContentValues().apply {
            put(COL_STATUS, status)
            uploadedAt?.let { put(COL_UPLOADED_AT, it) }
        }
        return helper.writableDatabase.update(
            TABLE_UPLOADS,
            values,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun setUploadedPaths(
        id: Long,
        photoPath: String,
        thumbnailPath: String,
        uploadedAt: Long
    ): Int {
        val values = ContentValues().apply {
            put(COL_PHOTO_B2_PATH, photoPath)
            put(COL_THUMBNAIL_B2_PATH, thumbnailPath)
            put(COL_UPLOADED_AT, uploadedAt)
            put(COL_STATUS, STATUS_UPLOADED)
        }
        return helper.writableDatabase.update(
            TABLE_UPLOADS,
            values,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun findByFilenameAndSize(filename: String, size: Long): UploadRecord? {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_FILENAME = ? AND $COL_SIZE = ?",
            arrayOf(filename, size.toString()),
            null,
            null,
            "$COL_ID DESC",
            "1"
        ).use { c ->
            return if (c.moveToFirst()) c.toRecord() else null
        }
    }

    fun getAll(): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            null,
            null,
            null,
            null,
            "$COL_DATE_TAKEN DESC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getPendingQueue(): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_STATUS = ?",
            arrayOf(STATUS_PENDING),
            null,
            null,
            "$COL_CREATED_AT ASC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getFailedRetryable(now: Long = System.currentTimeMillis()): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_STATUS = ? AND $COL_RETRY_COUNT < ? AND $COL_NEXT_RETRY_AT <= ?",
            arrayOf(STATUS_FAILED, MAX_RETRIES.toString(), now.toString()),
            null,
            null,
            "$COL_NEXT_RETRY_AT ASC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun updateRetry(id: Long, retryCount: Int, nextRetryAt: Long?): Int {
        val values = ContentValues().apply {
            put(COL_RETRY_COUNT, retryCount)
            if (nextRetryAt != null) put(COL_NEXT_RETRY_AT, nextRetryAt)
            else putNull(COL_NEXT_RETRY_AT)
        }
        return helper.writableDatabase.update(
            TABLE_UPLOADS,
            values,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun findBySha256(sha256: String): UploadRecord? {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_SHA256 = ?",
            arrayOf(sha256),
            null,
            null,
            "$COL_ID DESC",
            "1"
        ).use { c ->
            return if (c.moveToFirst()) c.toRecord() else null
        }
    }

    fun updateSha256(id: Long, sha256: String): Int {
        val values = ContentValues().apply {
            put(COL_SHA256, sha256)
        }
        return helper.writableDatabase.update(
            TABLE_UPLOADS,
            values,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    private fun Cursor.toRecord(): UploadRecord = UploadRecord(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        localUri = getString(getColumnIndexOrThrow(COL_LOCAL_URI)),
        filename = getString(getColumnIndexOrThrow(COL_FILENAME)),
        size = getLong(getColumnIndexOrThrow(COL_SIZE)),
        dateTaken = getLong(getColumnIndexOrThrow(COL_DATE_TAKEN)),
        photoB2Path = getStringOrNull(COL_PHOTO_B2_PATH),
        thumbnailB2Path = getStringOrNull(COL_THUMBNAIL_B2_PATH),
        status = getString(getColumnIndexOrThrow(COL_STATUS)),
        uploadedAt = getLongOrNull(COL_UPLOADED_AT),
        retryCount = getInt(getColumnIndexOrThrow(COL_RETRY_COUNT)),
        nextRetryAt = getLongOrNull(COL_NEXT_RETRY_AT),
        sha256 = getStringOrNull(COL_SHA256),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED_AT))
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getString(idx)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getLong(idx)
    }
}
