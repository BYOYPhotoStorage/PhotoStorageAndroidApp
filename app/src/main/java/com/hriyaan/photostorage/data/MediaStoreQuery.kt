package com.hriyaan.photostorage.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class MediaStoreQuery(private val context: Context) {

    fun queryAllPhotos(bucketIds: Set<String>? = null): List<MediaStorePhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val (selection, args) = bucketSelection(bucketIds)

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        ) ?: return emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            val out = ArrayList<MediaStorePhoto>(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val filename = c.getString(nameCol) ?: continue
                val size = c.getLong(sizeCol)
                val dateTaken = if (c.isNull(dateCol)) 0L else c.getLong(dateCol)
                val bucketId = c.getString(bucketIdCol)
                val bucketName = c.getString(bucketNameCol)
                out += MediaStorePhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    filename = filename,
                    size = size,
                    dateTakenMs = dateTaken,
                    bucketId = bucketId,
                    bucketName = bucketName
                )
            }
            return out
        }
    }

    fun queryPhotoFolders(): List<PhotoFolder> {
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        ) ?: return emptyList()

        val counts = mutableMapOf<String, Pair<String, Int>>()
        cursor.use { c ->
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val bucketId = c.getString(bucketIdCol)
                val bucketName = c.getString(bucketNameCol) ?: "Unknown"
                val current = counts[bucketId]
                counts[bucketId] = bucketName to ((current?.second ?: 0) + 1)
            }
        }

        val videoProjection = arrayOf(
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val videoCursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null,
            null,
            null
        )
        videoCursor?.use { c ->
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val bucketId = c.getString(bucketIdCol)
                val bucketName = c.getString(bucketNameCol) ?: "Unknown"
                val current = counts[bucketId]
                counts[bucketId] = bucketName to ((current?.second ?: 0) + 1)
            }
        }

        return counts.map { (bucketId, pair) ->
            PhotoFolder(
                bucketId = bucketId,
                bucketName = pair.first,
                itemCount = pair.second
            )
        }.sortedByDescending { it.itemCount }
    }

    private fun bucketSelection(bucketIds: Set<String>?): Pair<String?, Array<String>?> {
        if (bucketIds.isNullOrEmpty()) return null to null
        val placeholders = bucketIds.joinToString(",") { "?" }
        return "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)" to bucketIds.toTypedArray()
    }
}
