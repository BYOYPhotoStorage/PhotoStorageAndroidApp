package com.hriyaan.photostorage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class UploadDatabase(context: Context) {

    private val helper = Helper(context.applicationContext)

    val dao: UploadDao by lazy { UploadDao(helper) }

    val shareLinkDao: ShareLinkDao by lazy { ShareLinkDao(helper) }

    val shareGalleryDao: ShareGalleryDao by lazy { ShareGalleryDao(helper) }

    val writableDatabase: SQLiteDatabase
        get() = helper.writableDatabase

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
                    $COL_CLOUD_DELETED_AT INTEGER,
                    $COL_MEDIA_TYPE TEXT NOT NULL DEFAULT '${UploadDao.MEDIA_TYPE_PHOTO}',
                    $COL_ORIGINAL_PATH_B2 TEXT,
                    $COL_PENDING_LOCAL_DELETE INTEGER NOT NULL DEFAULT 0,
                    $COL_COMPRESSED INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_status ON $TABLE_UPLOADS($COL_STATUS)")
            db.execSQL("CREATE INDEX idx_filename_size ON $TABLE_UPLOADS($COL_FILENAME, $COL_SIZE)")
            db.execSQL("CREATE INDEX idx_sha256 ON $TABLE_UPLOADS($COL_SHA256)")
            db.execSQL("CREATE INDEX idx_cloud_deleted_at ON $TABLE_UPLOADS($COL_CLOUD_DELETED_AT)")
            db.execSQL("CREATE INDEX idx_date_taken ON $TABLE_UPLOADS($COL_DATE_TAKEN)")
            db.execSQL("CREATE INDEX idx_media_type ON $TABLE_UPLOADS($COL_MEDIA_TYPE)")
            db.execSQL("CREATE INDEX idx_pending_local_delete ON $TABLE_UPLOADS($COL_PENDING_LOCAL_DELETE)")

            db.execSQL(
                """
                CREATE TABLE $TABLE_SHARE_LINKS (
                    $COL_SHARE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_SHARE_UPLOAD_ID INTEGER NOT NULL,
                    $COL_SHARE_PHOTO_B2_PATH TEXT NOT NULL,
                    $COL_SHARE_URL TEXT NOT NULL,
                    $COL_SHARE_CREATED_AT INTEGER NOT NULL,
                    $COL_SHARE_EXPIRES_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_share_links_expires_at ON $TABLE_SHARE_LINKS($COL_SHARE_EXPIRES_AT)")
            db.execSQL("CREATE INDEX idx_share_links_upload_id ON $TABLE_SHARE_LINKS($COL_SHARE_UPLOAD_ID)")

            db.execSQL(
                """
                CREATE TABLE $TABLE_SHARE_GALLERIES (
                    $COL_SHARE_GALLERY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_SHARE_GALLERY_HTML_B2_PATH TEXT NOT NULL,
                    $COL_SHARE_GALLERY_URL TEXT NOT NULL,
                    $COL_SHARE_GALLERY_ITEM_COUNT INTEGER NOT NULL,
                    $COL_SHARE_GALLERY_CREATED_AT INTEGER NOT NULL,
                    $COL_SHARE_GALLERY_EXPIRES_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_share_galleries_expires_at ON $TABLE_SHARE_GALLERIES($COL_SHARE_GALLERY_EXPIRES_AT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SHARE_LINKS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SHARE_GALLERIES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_UPLOADS")
            onCreate(db)
        }
    }

    internal companion object {
        const val DB_NAME = "uploads.db"
        const val DB_VERSION = 5

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
        const val COL_MEDIA_TYPE = "media_type"
        const val COL_ORIGINAL_PATH_B2 = "original_path_b2"
        const val COL_PENDING_LOCAL_DELETE = "pending_local_delete"
        const val COL_COMPRESSED = "compressed"

        const val TABLE_SHARE_LINKS = "share_links"
        const val COL_SHARE_ID = "id"
        const val COL_SHARE_UPLOAD_ID = "upload_id"
        const val COL_SHARE_PHOTO_B2_PATH = "photo_b2_path"
        const val COL_SHARE_URL = "url"
        const val COL_SHARE_CREATED_AT = "created_at"
        const val COL_SHARE_EXPIRES_AT = "expires_at"

        const val TABLE_SHARE_GALLERIES = "share_galleries"
        const val COL_SHARE_GALLERY_ID = "id"
        const val COL_SHARE_GALLERY_HTML_B2_PATH = "html_b2_path"
        const val COL_SHARE_GALLERY_URL = "url"
        const val COL_SHARE_GALLERY_ITEM_COUNT = "item_count"
        const val COL_SHARE_GALLERY_CREATED_AT = "created_at"
        const val COL_SHARE_GALLERY_EXPIRES_AT = "expires_at"
    }
}
