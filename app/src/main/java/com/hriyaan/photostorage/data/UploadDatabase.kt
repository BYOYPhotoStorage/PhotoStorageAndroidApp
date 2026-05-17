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
                    $COL_UPLOADED_AT INTEGER,
                    $COL_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                    $COL_NEXT_RETRY_AT INTEGER,
                    $COL_SHA256 TEXT,
                    $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                    $COL_LOCAL_PRESENT INTEGER NOT NULL DEFAULT 1,
                    $COL_CLOUD_DELETED_AT INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_status ON $TABLE_UPLOADS($COL_STATUS)")
            db.execSQL("CREATE INDEX idx_filename_size ON $TABLE_UPLOADS($COL_FILENAME, $COL_SIZE)")
            db.execSQL("CREATE INDEX idx_sha256 ON $TABLE_UPLOADS($COL_SHA256)")
            db.execSQL("CREATE INDEX idx_cloud_deleted_at ON $TABLE_UPLOADS($COL_CLOUD_DELETED_AT)")
            db.execSQL("CREATE INDEX idx_date_taken ON $TABLE_UPLOADS($COL_DATE_TAKEN)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_UPLOADS")
            onCreate(db)
        }
    }

    internal companion object {
        const val DB_NAME = "uploads.db"
        const val DB_VERSION = 3

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
        const val COL_RETRY_COUNT = "retry_count"
        const val COL_NEXT_RETRY_AT = "next_retry_at"
        const val COL_SHA256 = "sha256"
        const val COL_CREATED_AT = "created_at"
        const val COL_LOCAL_PRESENT = "local_present"
        const val COL_CLOUD_DELETED_AT = "cloud_deleted_at"
    }
}
