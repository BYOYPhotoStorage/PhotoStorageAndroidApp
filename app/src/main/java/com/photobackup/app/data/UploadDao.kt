package com.photobackup.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import com.photobackup.app.data.UploadDatabase.Companion.COL_DATE_TAKEN
import com.photobackup.app.data.UploadDatabase.Companion.COL_FILENAME
import com.photobackup.app.data.UploadDatabase.Companion.COL_ID
import com.photobackup.app.data.UploadDatabase.Companion.COL_LOCAL_URI
import com.photobackup.app.data.UploadDatabase.Companion.COL_PHOTO_B2_PATH
import com.photobackup.app.data.UploadDatabase.Companion.COL_SIZE
import com.photobackup.app.data.UploadDatabase.Companion.COL_STATUS
import com.photobackup.app.data.UploadDatabase.Companion.COL_THUMBNAIL_B2_PATH
import com.photobackup.app.data.UploadDatabase.Companion.COL_UPLOADED_AT
import com.photobackup.app.data.UploadDatabase.Companion.TABLE_UPLOADS

class UploadDao internal constructor(private val helper: SQLiteOpenHelper) {

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_UPLOADED = "uploaded"
        const val STATUS_FAILED = "failed"
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

    private fun Cursor.toRecord(): UploadRecord = UploadRecord(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        localUri = getString(getColumnIndexOrThrow(COL_LOCAL_URI)),
        filename = getString(getColumnIndexOrThrow(COL_FILENAME)),
        size = getLong(getColumnIndexOrThrow(COL_SIZE)),
        dateTaken = getLong(getColumnIndexOrThrow(COL_DATE_TAKEN)),
        photoB2Path = getStringOrNull(COL_PHOTO_B2_PATH),
        thumbnailB2Path = getStringOrNull(COL_THUMBNAIL_B2_PATH),
        status = getString(getColumnIndexOrThrow(COL_STATUS)),
        uploadedAt = getLongOrNull(COL_UPLOADED_AT)
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
