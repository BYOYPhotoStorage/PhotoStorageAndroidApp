package com.hriyaan.photostorage.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_CLOUD_DELETED_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_COMPRESSED
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_CREATED_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_DATE_TAKEN
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_FILENAME
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_ID
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_LOCAL_PRESENT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_LOCAL_URI
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_MEDIA_TYPE
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_NEXT_RETRY_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_ORIGINAL_PATH_B2
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_PENDING_LOCAL_DELETE
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
        const val STATUS_CLOUD_DELETED = "cloud_deleted"

        const val MEDIA_TYPE_PHOTO = "photo"
        const val MEDIA_TYPE_VIDEO = "video"

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
            put(COL_LOCAL_PRESENT, if (record.localPresent) 1 else 0)
            record.cloudDeletedAt?.let { put(COL_CLOUD_DELETED_AT, it) }
            put(COL_MEDIA_TYPE, record.mediaType)
            record.originalPathB2?.let { put(COL_ORIGINAL_PATH_B2, it) }
            put(COL_PENDING_LOCAL_DELETE, if (record.pendingLocalDelete) 1 else 0)
            put(COL_COMPRESSED, if (record.compressed) 1 else 0)
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

    fun setUploadedVideoPaths(
        id: Long,
        videoPath: String,
        thumbnailPath: String,
        uploadedAt: Long,
        compressed: Boolean,
        originalPathB2: String? = null
    ): Int {
        val values = ContentValues().apply {
            put(COL_PHOTO_B2_PATH, videoPath)
            put(COL_THUMBNAIL_B2_PATH, thumbnailPath)
            put(COL_UPLOADED_AT, uploadedAt)
            put(COL_STATUS, STATUS_UPLOADED)
            put(COL_COMPRESSED, if (compressed) 1 else 0)
            if (originalPathB2 != null) put(COL_ORIGINAL_PATH_B2, originalPathB2)
            else putNull(COL_ORIGINAL_PATH_B2)
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

    fun getCloudView(): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_STATUS = ? AND $COL_CLOUD_DELETED_AT IS NULL",
            arrayOf(STATUS_UPLOADED),
            null,
            null,
            "$COL_DATE_TAKEN DESC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getCloudViewBefore(dateTaken: Long): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_STATUS = ? AND $COL_CLOUD_DELETED_AT IS NULL AND $COL_DATE_TAKEN < ?",
            arrayOf(STATUS_UPLOADED, dateTaken.toString()),
            null,
            null,
            "$COL_DATE_TAKEN DESC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getLatestUploadedAt(): Long? {
        helper.readableDatabase.rawQuery(
            "SELECT MAX($COL_UPLOADED_AT) FROM $TABLE_UPLOADS WHERE $COL_STATUS = ?",
            arrayOf(STATUS_UPLOADED)
        ).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    fun setLocalPresent(id: Long, present: Boolean): Int {
        val values = ContentValues().apply {
            put(COL_LOCAL_PRESENT, if (present) 1 else 0)
        }
        return helper.writableDatabase.update(
            TABLE_UPLOADS,
            values,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun softDeleteCloud(id: Long, now: Long): Int {
        val values = ContentValues().apply {
            put(COL_STATUS, STATUS_CLOUD_DELETED)
            put(COL_CLOUD_DELETED_AT, now)
        }
        return helper.writableDatabase.update(
            TABLE_UPLOADS,
            values,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun getSoftDeletedOlderThan(cutoff: Long): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_STATUS = ? AND $COL_CLOUD_DELETED_AT <= ?",
            arrayOf(STATUS_CLOUD_DELETED, cutoff.toString()),
            null,
            null,
            "$COL_CLOUD_DELETED_AT ASC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun hardDelete(id: Long): Int {
        return helper.writableDatabase.delete(
            TABLE_UPLOADS,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun replaceAll(records: List<UploadRecord>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_UPLOADS, null, null)
            for (record in records) {
                val values = ContentValues().apply {
                    if (record.id != 0L) put(COL_ID, record.id)
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
                    put(COL_LOCAL_PRESENT, if (record.localPresent) 1 else 0)
                    record.cloudDeletedAt?.let { put(COL_CLOUD_DELETED_AT, it) }
                    put(COL_MEDIA_TYPE, record.mediaType)
                    record.originalPathB2?.let { put(COL_ORIGINAL_PATH_B2, it) }
                    put(COL_PENDING_LOCAL_DELETE, if (record.pendingLocalDelete) 1 else 0)
                    put(COL_COMPRESSED, if (record.compressed) 1 else 0)
                }
                db.insertOrThrow(TABLE_UPLOADS, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getByMediaType(mediaType: String): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_MEDIA_TYPE = ?",
            arrayOf(mediaType),
            null,
            null,
            "$COL_DATE_TAKEN DESC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun countByMediaType(mediaType: String): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_UPLOADS WHERE $COL_MEDIA_TYPE = ? AND $COL_STATUS = ?",
            arrayOf(mediaType, STATUS_UPLOADED)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun sumSizeByMediaType(mediaType: String): Long {
        helper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM($COL_SIZE), 0) FROM $TABLE_UPLOADS WHERE $COL_MEDIA_TYPE = ? AND $COL_STATUS = ?",
            arrayOf(mediaType, STATUS_UPLOADED)
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun markPendingLocalDelete(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val db = helper.writableDatabase
        var affected = 0
        db.beginTransaction()
        try {
            ids.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val sql = "UPDATE $TABLE_UPLOADS SET $COL_PENDING_LOCAL_DELETE = 1 " +
                    "WHERE $COL_ID IN ($placeholders)"
                db.compileStatement(sql).use { stmt ->
                    chunk.forEachIndexed { index, id -> stmt.bindLong(index + 1, id) }
                    affected += stmt.executeUpdateDelete()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return affected
    }

    fun getPendingLocalDelete(): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_PENDING_LOCAL_DELETE = 1 AND $COL_STATUS = ?",
            arrayOf(STATUS_UPLOADED),
            null,
            null,
            "$COL_DATE_TAKEN DESC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getEligibleForLocalDelete(olderThanUploadedAt: Long): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_STATUS = ? AND $COL_UPLOADED_AT <= ? AND $COL_PENDING_LOCAL_DELETE = 0 AND $COL_LOCAL_PRESENT = 1",
            arrayOf(STATUS_UPLOADED, olderThanUploadedAt.toString()),
            null,
            null,
            "$COL_UPLOADED_AT ASC"
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getOldestUploaded(limit: Int): List<UploadRecord> {
        helper.readableDatabase.query(
            TABLE_UPLOADS,
            null,
            "$COL_STATUS = ? AND $COL_LOCAL_PRESENT = 1 AND $COL_PENDING_LOCAL_DELETE = 0",
            arrayOf(STATUS_UPLOADED),
            null,
            null,
            "$COL_UPLOADED_AT ASC",
            limit.toString()
        ).use { c ->
            val out = ArrayList<UploadRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun clearPendingLocalDelete(id: Long): Int {
        val values = ContentValues().apply {
            put(COL_PENDING_LOCAL_DELETE, 0)
        }
        return helper.writableDatabase.update(
            TABLE_UPLOADS,
            values,
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun countUploadsSince(timestamp: Long): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_UPLOADS WHERE $COL_UPLOADED_AT > ?",
            arrayOf(timestamp.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
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
        uploadedAt = getLongOrNull(COL_UPLOADED_AT),
        retryCount = getInt(getColumnIndexOrThrow(COL_RETRY_COUNT)),
        nextRetryAt = getLongOrNull(COL_NEXT_RETRY_AT),
        sha256 = getStringOrNull(COL_SHA256),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        localPresent = getInt(getColumnIndexOrThrow(COL_LOCAL_PRESENT)) == 1,
        cloudDeletedAt = getLongOrNull(COL_CLOUD_DELETED_AT),
        mediaType = getString(getColumnIndexOrThrow(COL_MEDIA_TYPE)),
        originalPathB2 = getStringOrNull(COL_ORIGINAL_PATH_B2),
        pendingLocalDelete = getInt(getColumnIndexOrThrow(COL_PENDING_LOCAL_DELETE)) == 1,
        compressed = getInt(getColumnIndexOrThrow(COL_COMPRESSED)) == 1
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
