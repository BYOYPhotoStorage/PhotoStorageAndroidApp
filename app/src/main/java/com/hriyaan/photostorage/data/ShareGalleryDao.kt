package com.hriyaan.photostorage.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_GALLERY_CREATED_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_GALLERY_EXPIRES_AT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_GALLERY_HTML_B2_PATH
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_GALLERY_ID
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_GALLERY_ITEM_COUNT
import com.hriyaan.photostorage.data.UploadDatabase.Companion.COL_SHARE_GALLERY_URL
import com.hriyaan.photostorage.data.UploadDatabase.Companion.TABLE_SHARE_GALLERIES

class ShareGalleryDao internal constructor(private val helper: SQLiteOpenHelper) {

    companion object {
        private const val POST_EXPIRY_VISIBILITY_MS = 24L * 60L * 60L * 1000L
    }

    fun insert(record: ShareGalleryRecord): Long {
        val values = ContentValues().apply {
            put(COL_SHARE_GALLERY_HTML_B2_PATH, record.htmlB2Path)
            put(COL_SHARE_GALLERY_URL, record.url)
            put(COL_SHARE_GALLERY_ITEM_COUNT, record.itemCount)
            put(COL_SHARE_GALLERY_CREATED_AT, record.createdAt)
            put(COL_SHARE_GALLERY_EXPIRES_AT, record.expiresAt)
        }
        return helper.writableDatabase.insertOrThrow(TABLE_SHARE_GALLERIES, null, values)
    }

    fun getActive(now: Long): List<ShareGalleryRecord> {
        helper.readableDatabase.query(
            TABLE_SHARE_GALLERIES,
            null,
            "$COL_SHARE_GALLERY_EXPIRES_AT + ? > ?",
            arrayOf(POST_EXPIRY_VISIBILITY_MS.toString(), now.toString()),
            null,
            null,
            "$COL_SHARE_GALLERY_CREATED_AT DESC"
        ).use { c ->
            val out = ArrayList<ShareGalleryRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun getOlderThan(cutoff: Long): List<ShareGalleryRecord> {
        helper.readableDatabase.query(
            TABLE_SHARE_GALLERIES,
            null,
            "$COL_SHARE_GALLERY_EXPIRES_AT < ?",
            arrayOf(cutoff.toString()),
            null,
            null,
            "$COL_SHARE_GALLERY_EXPIRES_AT ASC"
        ).use { c ->
            val out = ArrayList<ShareGalleryRecord>(c.count)
            while (c.moveToNext()) out += c.toRecord()
            return out
        }
    }

    fun deleteOlderThan(cutoff: Long): Int {
        return helper.writableDatabase.delete(
            TABLE_SHARE_GALLERIES,
            "$COL_SHARE_GALLERY_EXPIRES_AT < ?",
            arrayOf(cutoff.toString())
        )
    }

    private fun Cursor.toRecord(): ShareGalleryRecord = ShareGalleryRecord(
        id = getLong(getColumnIndexOrThrow(COL_SHARE_GALLERY_ID)),
        htmlB2Path = getString(getColumnIndexOrThrow(COL_SHARE_GALLERY_HTML_B2_PATH)),
        url = getString(getColumnIndexOrThrow(COL_SHARE_GALLERY_URL)),
        itemCount = getInt(getColumnIndexOrThrow(COL_SHARE_GALLERY_ITEM_COUNT)),
        createdAt = getLong(getColumnIndexOrThrow(COL_SHARE_GALLERY_CREATED_AT)),
        expiresAt = getLong(getColumnIndexOrThrow(COL_SHARE_GALLERY_EXPIRES_AT))
    )
}
