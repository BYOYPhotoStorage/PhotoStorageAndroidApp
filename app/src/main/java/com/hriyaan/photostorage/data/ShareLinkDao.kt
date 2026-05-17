package com.hriyaan.photostorage.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_CREATED_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_EXPIRES_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_ID
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_PHOTO_B2_PATH
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_UPLOAD_ID
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_URL
import com.hriyaan.photostorage.data.UploadDatabase.Companion.TABLE_SHARE_LINKS

class ShareLinkDao internal constructor(private val helper: SQLiteOpenHelper) {

    companion object {
        private const val POST_EXPIRY_VISIBILITY_MS = 24L * 60L * 60L * 1000L
    }

    fun insert(record: ShareLinkRecord): Long {
        val values = ContentValues().apply {
            put(COL_SHARE_UPLOAD_ID, record.uploadId)
            put(COL_SHARE_PHOTO_B2_PATH, record.photoB2Path)
            put(COL_SHARE_URL, record.url)
            put(COL_SHARE_CREATED_AT, record.createdAt)
            put(COL_SHARE_EXPIRES_AT, record.expiresAt)
        }
        return helper.writableDatabase.insertOrThrow(TABLE_SHARE_LINKS, null, values)
    }

    fun getActive(now: Long): List<ShareLinkRecord> {
        helper.readableDatabase.query(
            TABLE_SHARE_LINKS,
            null,
            "$COL_SHARE_EXPIRES_AT + ? > ?",
            arrayOf(POST_EXPIRY_VISIBILITY_MS.toString(), now.toString()),
            null,
            null,
            "$COL_SHARE_CREATED_AT DESC"
        ).use { c ->
            val out = ArrayList<ShareLinkRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getOlderThan(cutoff: Long): List<ShareLinkRecord> {
        helper.readableDatabase.query(
            TABLE_SHARE_LINKS,
            null,
            "$COL_SHARE_EXPIRES_AT < ?",
            arrayOf(cutoff.toString()),
            null,
            null,
            "$COL_SHARE_EXPIRES_AT ASC"
        ).use { c ->
            val out = ArrayList<ShareLinkRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun deleteOlderThan(cutoff: Long): Int {
        return helper.writableDatabase.delete(
            TABLE_SHARE_LINKS,
            "$COL_SHARE_EXPIRES_AT < ?",
            arrayOf(cutoff.toString())
        )
    }

    private fun Cursor.toRecord(): ShareLinkRecord = ShareLinkRecord(
        id = getLong(getColumnIndexOrThrow(COL_SHARE_ID)),
        uploadId = getLong(getColumnIndexOrThrow(COL_SHARE_UPLOAD_ID)),
        photoB2Path = getString(getColumnIndexOrThrow(COL_SHARE_PHOTO_B2_PATH)),
        url = getString(getColumnIndexOrThrow(COL_SHARE_URL)),
        createdAt = getLong(getColumnIndexOrThrow(COL_SHARE_CREATED_AT)),
        expiresAt = getLong(getColumnIndexOrThrow(COL_SHARE_EXPIRES_AT))
    )
}
