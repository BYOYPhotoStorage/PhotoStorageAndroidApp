package com.hriyaan.photostorage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class UploadDatabase(context: Context) {

    private val helper = Helper(context.applicationContext)

    val dao: UploadDao by lazy { UploadDao(helper) }

    fun close() {
        helper.close()
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_UPLOADS (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_LOCAL_URI TEXT NOT NULL,
                    $COL_FILENAME TEXT NOT NULL,
                    $COL_SIZE INTEGER NOT NULL,
                    $COL_DATE_TAKEN INTEGER NOT NULL,
                    $COL_PHOTO_B2_PATH TEXT,
                    $COL_THUMBNAIL_B2_PATH TEXT,
                    $COL_STATUS TEXT NOT NULL DEFAULT '${UploadDao.STATUS_PENDING}',
                    $COL_UPLOADED_AT INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_status ON $TABLE_UPLOADS($COL_STATUS)")
            db.execSQL("CREATE INDEX idx_filename_size ON $TABLE_UPLOADS($COL_FILENAME, $COL_SIZE)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // MVP: schema version 1 only. Future migrations land here.
        }
    }

    internal companion object {
        const val DB_NAME = "uploads.db"
        const val DB_VERSION = 1

        const val TABLE_UPLOADS = "uploads"
        const val COL_ID = "id"
        const val COL_LOCAL_URI = "local_uri"
        const val COL_FILENAME = "filename"
        const val COL_SIZE = "size"
        const val COL_DATE_TAKEN = "date_taken"
        const val COL_PHOTO_B2_PATH = "photo_b2_path"
        const val COL_THUMBNAIL_B2_PATH = "thumbnail_b2_path"
        const val COL_STATUS = "status"
        const val COL_UPLOADED_AT = "uploaded_at"
    }
}
