package com.hriyaan.photostorage.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class MediaStoreQuery(private val context: Context) {

    fun queryAllPhotos(): List<MediaStorePhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN
        )

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        ) ?: return emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            val out = ArrayList<MediaStorePhoto>(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val filename = c.getString(nameCol) ?: continue
                val size = c.getLong(sizeCol)
                val dateTaken = if (c.isNull(dateCol)) 0L else c.getLong(dateCol)
                out += MediaStorePhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    filename = filename,
                    size = size,
                    dateTakenMs = dateTaken
                )
            }
            return out
        }
    }
}
