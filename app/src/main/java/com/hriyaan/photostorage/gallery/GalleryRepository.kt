package com.hriyaan.photostorage.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.hriyaan.photostorage.data.MediaStorePhoto
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class GalleryRepository(
    private val context: Context,
    private val uploadDao: UploadDao,
    private val prefsStore: PrefsStore
) {

    private val invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16
    )

    private val cacheLock = Any()
    private val cache = mutableMapOf<GalleryViewMode, List<GalleryItem>>()

    fun observe(mode: GalleryViewMode): Flow<List<GalleryItem>> = flow {
        synchronized(cacheLock) { cache[mode] }?.let { emit(it) }
        emit(loadAndCache(mode))
        invalidations.collect { emit(loadAndCache(mode)) }
    }.flowOn(Dispatchers.IO)

    suspend fun load(mode: GalleryViewMode): List<GalleryItem> = withContext(Dispatchers.IO) {
        loadAndCache(mode)
    }

    fun invalidate() {
        synchronized(cacheLock) { cache.clear() }
        invalidations.tryEmit(Unit)
    }

    private fun loadAndCache(mode: GalleryViewMode): List<GalleryItem> {
        val items = when (mode) {
            GalleryViewMode.LOCAL -> loadLocal()
            GalleryViewMode.CLOUD -> loadCloud()
            GalleryViewMode.MERGED -> loadMerged()
        }
        synchronized(cacheLock) { cache[mode] = items }
        return items
    }

    private fun loadLocal(): List<GalleryItem> {
        val mediaPhotos = queryAllMediaStorePhotos()
        val recordsByNatKey = uploadDao.getAll().associateBy { it.filename to it.size }
        return mediaPhotos.map { photo ->
            val record = recordsByNatKey[photo.filename to photo.size]
            val queued = if (record != null && record.status in QUEUE_STATUSES) record else null
            GalleryItem.LocalOnly(
                id = "local:${photo.uri}",
                dateTaken = photo.dateTakenMs,
                filename = photo.filename,
                thumbnailSource = ThumbnailSource.LocalUri(photo.uri),
                mediaStoreUri = photo.uri,
                sizeBytes = photo.size,
                queuedRecord = queued,
                mediaType = photo.mediaType
            )
        }
    }

    private fun loadCloud(): List<GalleryItem> {
        val records = uploadDao.getCloudView()
        val mediaByNatKey: Map<Pair<String, Long>, MediaStorePhoto> =
            if (records.any { it.localPresent }) {
                queryAllMediaStorePhotos().associateBy { it.filename to it.size }
            } else {
                emptyMap()
            }
        return records.map { record ->
            val matchingMedia = if (record.localPresent) {
                mediaByNatKey[record.filename to record.size]
            } else {
                null
            }
            val thumb = if (matchingMedia != null) {
                ThumbnailSource.LocalUri(matchingMedia.uri)
            } else {
                ThumbnailSource.B2Path(record.thumbnailB2Path.orEmpty())
            }
            GalleryItem.CloudOnly(
                id = "cloud:${record.id}",
                dateTaken = record.dateTaken,
                filename = record.filename,
                thumbnailSource = thumb,
                uploadRecord = record
            )
        }
    }

    private fun loadMerged(): List<GalleryItem> {
        val latestUploadedAt = uploadDao.getLatestUploadedAt() ?: 0L
        val uploaded = uploadDao.getCloudView()
        val allRecords = uploadDao.getAll()
        val mediaAll = queryAllMediaStorePhotos()

        val mediaByUriStr = mediaAll.associateBy { it.uri.toString() }
        val mediaByNatKey = mediaAll.associateBy { it.filename to it.size }
        val recordsByNatKey = allRecords.associateBy { it.filename to it.size }

        val claimedMediaUris = mutableSetOf<String>()
        val items = mutableListOf<GalleryItem>()

        for (record in uploaded) {
            val match: MediaStorePhoto? = mediaByUriStr[record.localUri]
                ?: mediaByNatKey[record.filename to record.size]

            when {
                match != null && record.localPresent -> {
                    claimedMediaUris += match.uri.toString()
                    items += GalleryItem.Synced(
                        id = "synced:${record.id}",
                        dateTaken = record.dateTaken,
                        filename = record.filename,
                        thumbnailSource = ThumbnailSource.LocalUri(match.uri),
                        mediaStoreUri = match.uri,
                        uploadRecord = record
                    )
                }
                match != null -> {
                    claimedMediaUris += match.uri.toString()
                    items += GalleryItem.CloudOnly(
                        id = "cloud:${record.id}",
                        dateTaken = record.dateTaken,
                        filename = record.filename,
                        thumbnailSource = ThumbnailSource.LocalUri(match.uri),
                        uploadRecord = record
                    )
                }
                else -> {
                    items += GalleryItem.CloudOnly(
                        id = "cloud:${record.id}",
                        dateTaken = record.dateTaken,
                        filename = record.filename,
                        thumbnailSource = ThumbnailSource.B2Path(record.thumbnailB2Path.orEmpty()),
                        uploadRecord = record
                    )
                }
            }
        }

        for (photo in mediaAll) {
            if (photo.dateTakenMs <= latestUploadedAt) continue
            if (photo.uri.toString() in claimedMediaUris) continue
            val record = recordsByNatKey[photo.filename to photo.size]
            val queued: UploadRecord? = if (record != null && record.status in QUEUE_STATUSES) record else null
            items += GalleryItem.LocalOnly(
                id = "local:${photo.uri}",
                dateTaken = photo.dateTakenMs,
                filename = photo.filename,
                thumbnailSource = ThumbnailSource.LocalUri(photo.uri),
                mediaStoreUri = photo.uri,
                sizeBytes = photo.size,
                queuedRecord = queued
            )
        }

        return items.sortedByDescending { it.dateTaken }
    }

    private fun queryAllMediaStorePhotos(): List<MediaStorePhoto> {
        val out = ArrayList<MediaStorePhoto>()
        val selectedBuckets = prefsStore.getSelectedBucketIds()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val (imageSelection, imageArgs) = bucketSelection(selectedBuckets, isImage = true)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            imageSelection,
            imageArgs,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

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
                    mediaType = "photo",
                    bucketId = bucketId,
                    bucketName = bucketName
                )
            }
        }

        if (prefsStore.getVideosEnabled()) {
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )
            val (videoSelection, videoArgs) = bucketSelection(selectedBuckets, isImage = false)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                videoSelection,
                videoArgs,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

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
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        ),
                        filename = filename,
                        size = size,
                        dateTakenMs = dateTaken,
                        mediaType = "video",
                        bucketId = bucketId,
                        bucketName = bucketName
                    )
                }
            }
        }

        return out.sortedByDescending { it.dateTakenMs }
    }

    private fun bucketSelection(bucketIds: Set<String>?, isImage: Boolean): Pair<String?, Array<String>?> {
        if (bucketIds.isNullOrEmpty()) return null to null
        val column = if (isImage) MediaStore.Images.Media.BUCKET_ID else MediaStore.Video.Media.BUCKET_ID
        val placeholders = bucketIds.joinToString(",") { "?" }
        return "$column IN ($placeholders)" to bucketIds.toTypedArray()
    }

    companion object {
        private val QUEUE_STATUSES = setOf(
            UploadDao.STATUS_PENDING,
            UploadDao.STATUS_UPLOADING,
            UploadDao.STATUS_FAILED,
            UploadDao.STATUS_PERMANENTLY_FAILED
        )
    }
}
