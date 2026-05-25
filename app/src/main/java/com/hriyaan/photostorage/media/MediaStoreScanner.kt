package com.hriyaan.photostorage.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.hriyaan.photostorage.PhotoBackupApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreScanner(private val context: Context) {

    suspend fun scanImages(
        since: Long?,
        dateColumn: String = MediaStore.Images.Media.DATE_ADDED,
        bucketIds: Set<String>? = null
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val (selection, args) = buildSelection(dateColumn, since, bucketIds, isImage = true)

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ) ?: return@withContext emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateTakenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            val out = ArrayList<MediaItem>(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val filename = c.getString(nameCol) ?: continue
                val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)
                val dateTaken = if (c.isNull(dateTakenCol)) {
                    c.getLong(dateAddedCol) * 1000L
                } else {
                    c.getLong(dateTakenCol)
                }
                out += MediaItem(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    filename = filename,
                    size = size,
                    dateTaken = dateTaken,
                    mediaType = MediaType.PHOTO,
                    durationMs = null
                )
            }
            out
        }
    }

    suspend fun scanVideos(
        since: Long?,
        dateColumn: String = MediaStore.Video.Media.DATE_ADDED,
        bucketIds: Set<String>? = null
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as PhotoBackupApp
        if (!app.prefsStore.getVideosEnabled()) return@withContext emptyList()
        if (!hasVideoReadPermission()) {
            Log.w(TAG, "READ_MEDIA_VIDEO not granted; skipping video scan")
            return@withContext emptyList()
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        )
        val (selection, args) = buildSelection(dateColumn, since, bucketIds, isImage = false)

        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        ) ?: return@withContext emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateTakenCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            val out = ArrayList<MediaItem>(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val filename = c.getString(nameCol) ?: continue
                val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)
                if (size == 0L) continue
                val dateTaken = if (c.isNull(dateTakenCol)) {
                    c.getLong(dateAddedCol) * 1000L
                } else {
                    c.getLong(dateTakenCol)
                }
                val durationMs = if (c.isNull(durationCol)) null else c.getLong(durationCol)
                out += MediaItem(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    filename = filename,
                    size = size,
                    dateTaken = dateTaken,
                    mediaType = MediaType.VIDEO,
                    durationMs = durationMs
                )
            }
            out
        }
    }

    private fun buildSelection(
        dateColumn: String,
        since: Long?,
        bucketIds: Set<String>?,
        isImage: Boolean
    ): Pair<String?, Array<String>?> {
        val sinceClause = sinceClause(dateColumn, since)
        val bucketClause = bucketClause(bucketIds, isImage)

        return when {
            sinceClause.first != null && bucketClause.first != null -> {
                val allArgs = sinceClause.second!! + bucketClause.second!!
                "${sinceClause.first} AND ${bucketClause.first}" to allArgs
            }
            sinceClause.first != null -> sinceClause
            bucketClause.first != null -> bucketClause
            else -> null to null
        }
    }

    private fun sinceClause(column: String, since: Long?): Pair<String?, Array<String>?> {
        if (since == null) return null to null
        val value = if (column == MediaStore.Images.Media.DATE_TAKEN || column == MediaStore.Video.Media.DATE_TAKEN) {
            since.toString()
        } else {
            (since / 1000L).toString()
        }
        return "$column > ?" to arrayOf(value)
    }

    private fun bucketClause(bucketIds: Set<String>?, isImage: Boolean): Pair<String?, Array<String>?> {
        if (bucketIds.isNullOrEmpty()) return null to null
        val column = if (isImage) {
            MediaStore.Images.Media.BUCKET_ID
        } else {
            MediaStore.Video.Media.BUCKET_ID
        }
        val placeholders = bucketIds.joinToString(",") { "?" }
        return "$column IN ($placeholders)" to bucketIds.toTypedArray()
    }

    private fun hasVideoReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "MediaStoreScanner"
    }
}
