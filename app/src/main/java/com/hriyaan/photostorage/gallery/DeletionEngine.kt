package com.hriyaan.photostorage.gallery

import android.content.Context
import android.net.Uri
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

fun interface MediaStoreDeleteLauncher {
    suspend fun launch(uris: List<Uri>): Boolean
}

sealed class DeletionResult {
    object Success : DeletionResult()
    data class Refused(val reason: String) : DeletionResult()
    data class Failed(val cause: Throwable, val partialSuccess: Boolean) : DeletionResult()
}

data class BatchDeletionResult(
    val succeeded: Int,
    val refused: Int,
    val failed: Int,
    val summary: String
)

class DeletionEngine(
    @Suppress("unused") private val context: Context,
    private val uploadDao: UploadDao,
    private val s3Uploader: S3Uploader,
    private val galleryRepository: GalleryRepository,
    private val mediaStoreDeleteLauncher: MediaStoreDeleteLauncher
) {

    suspend fun delete(item: GalleryItem, mode: GalleryViewMode): DeletionResult {
        val result = deleteOne(item, mode, batchApproved = null)
        if (result.changedState) galleryRepository.invalidate()
        return result
    }

    suspend fun deleteBatch(items: List<GalleryItem>, mode: GalleryViewMode): BatchDeletionResult {
        val urisToPrompt = collectLocalUris(items, mode)
        val batchApproved: Boolean? = if (urisToPrompt.isNotEmpty()) {
            mediaStoreDeleteLauncher.launch(urisToPrompt)
        } else {
            null
        }

        var succeeded = 0
        var refused = 0
        var failed = 0
        val refusalReasons = linkedSetOf<String>()

        for (item in items) {
            when (val r = deleteOne(item, mode, batchApproved = batchApproved)) {
                is DeletionResult.Success -> succeeded++
                is DeletionResult.Refused -> {
                    refused++
                    refusalReasons += r.reason
                }
                is DeletionResult.Failed -> failed++
            }
        }

        galleryRepository.invalidate()

        val parts = mutableListOf<String>()
        if (succeeded > 0) parts += "$succeeded deleted"
        if (refused > 0) parts += "$refused skipped (${refusalReasons.joinToString(", ")})"
        if (failed > 0) parts += "$failed failed"
        return BatchDeletionResult(succeeded, refused, failed, parts.joinToString(" · "))
    }

    private fun collectLocalUris(items: List<GalleryItem>, mode: GalleryViewMode): List<Uri> {
        if (mode == GalleryViewMode.CLOUD) return emptyList()
        return items.mapNotNull { item ->
            when (item) {
                is GalleryItem.LocalOnly -> {
                    if (item.queuedRecord?.status == UploadDao.STATUS_UPLOADING) null
                    else item.mediaStoreUri
                }
                is GalleryItem.Synced -> {
                    val s = item.uploadRecord.status
                    when {
                        s == UploadDao.STATUS_UPLOADING -> null
                        mode == GalleryViewMode.MERGED && s == UploadDao.STATUS_PERMANENTLY_FAILED -> null
                        else -> item.mediaStoreUri
                    }
                }
                is GalleryItem.CloudOnly -> null
            }
        }
    }

    private suspend fun deleteOne(
        item: GalleryItem,
        mode: GalleryViewMode,
        batchApproved: Boolean?
    ): DeletionResult = when (mode) {
        GalleryViewMode.LOCAL -> deleteLocalMode(item, batchApproved)
        GalleryViewMode.CLOUD -> deleteCloudMode(item)
        GalleryViewMode.MERGED -> deleteMergedMode(item, batchApproved)
    }

    private suspend fun deleteLocalMode(item: GalleryItem, batchApproved: Boolean?): DeletionResult {
        return when (item) {
            is GalleryItem.CloudOnly -> DeletionResult.Refused("Photo is not on this device.")
            is GalleryItem.LocalOnly -> {
                val queued = item.queuedRecord
                if (queued?.status == UploadDao.STATUS_UPLOADING) {
                    return DeletionResult.Refused("Cancel the upload first.")
                }
                val localFailure = performMediaStoreDelete(listOf(item.mediaStoreUri), batchApproved)
                if (localFailure != null) return localFailure
                if (queued != null) {
                    when (queued.status) {
                        UploadDao.STATUS_UPLOADED -> uploadDao.setLocalPresent(queued.id, false)
                        else -> uploadDao.hardDelete(queued.id)
                    }
                }
                DeletionResult.Success
            }
            is GalleryItem.Synced -> {
                val localFailure = performMediaStoreDelete(listOf(item.mediaStoreUri), batchApproved)
                if (localFailure != null) return localFailure
                uploadDao.setLocalPresent(item.uploadRecord.id, false)
                DeletionResult.Success
            }
        }
    }

    private suspend fun deleteCloudMode(item: GalleryItem): DeletionResult {
        val record: UploadRecord = when (item) {
            is GalleryItem.LocalOnly -> return DeletionResult.Refused("Photo is not in the cloud.")
            is GalleryItem.CloudOnly -> item.uploadRecord
            is GalleryItem.Synced -> item.uploadRecord
        }
        if (record.status == UploadDao.STATUS_UPLOADING) {
            return DeletionResult.Refused("Wait for upload to complete.")
        }
        if (record.status == UploadDao.STATUS_PERMANENTLY_FAILED) {
            return DeletionResult.Refused("Discard the failed upload first.")
        }
        return deleteCloudObjects(record).fold(
            onSuccess = {
                uploadDao.softDeleteCloud(record.id, System.currentTimeMillis())
                DeletionResult.Success
            },
            onFailure = { DeletionResult.Failed(it, partialSuccess = false) }
        )
    }

    private suspend fun deleteMergedMode(item: GalleryItem, batchApproved: Boolean?): DeletionResult {
        return when (item) {
            is GalleryItem.LocalOnly -> deleteLocalMode(item, batchApproved)
            is GalleryItem.CloudOnly -> deleteCloudMode(item)
            is GalleryItem.Synced -> {
                val record = item.uploadRecord
                if (record.status == UploadDao.STATUS_UPLOADING) {
                    return DeletionResult.Refused("Wait for upload to complete.")
                }
                if (record.status == UploadDao.STATUS_PERMANENTLY_FAILED) {
                    return DeletionResult.Refused("Discard the failed upload first.")
                }

                val cloudResult = deleteCloudObjects(record)
                val cloudOk = cloudResult.isSuccess
                if (cloudOk) uploadDao.softDeleteCloud(record.id, System.currentTimeMillis())

                val localFailure = performMediaStoreDelete(listOf(item.mediaStoreUri), batchApproved)
                val localOk = localFailure == null

                when {
                    cloudOk && localOk -> {
                        uploadDao.hardDelete(record.id)
                        DeletionResult.Success
                    }
                    cloudOk -> {
                        uploadDao.setLocalPresent(record.id, false)
                        DeletionResult.Failed(
                            cause = failureCause(localFailure, "Local delete failed."),
                            partialSuccess = true
                        )
                    }
                    localOk -> {
                        uploadDao.setLocalPresent(record.id, false)
                        DeletionResult.Failed(
                            cause = cloudResult.exceptionOrNull() ?: RuntimeException("Cloud delete failed."),
                            partialSuccess = true
                        )
                    }
                    else -> {
                        val cloudErr = cloudResult.exceptionOrNull()?.message ?: "cloud failed"
                        val localErr = failureCause(localFailure, "local failed").message ?: "local failed"
                        DeletionResult.Failed(
                            cause = RuntimeException("$cloudErr; $localErr"),
                            partialSuccess = false
                        )
                    }
                }
            }
        }
    }

    private suspend fun deleteCloudObjects(record: UploadRecord): Result<Unit> = coroutineScope {
        val photoPath = record.photoB2Path
        val thumbPath = record.thumbnailB2Path
        if (photoPath.isNullOrEmpty() && thumbPath.isNullOrEmpty()) {
            return@coroutineScope Result.success(Unit)
        }
        val jobs = listOfNotNull(
            photoPath?.takeIf { it.isNotEmpty() }?.let { p -> async { s3Uploader.deleteObject(p) } },
            thumbPath?.takeIf { it.isNotEmpty() }?.let { p -> async { s3Uploader.deleteObject(p) } }
        )
        val results = jobs.awaitAll()
        val failure = results.firstOrNull { it.isFailure }
        if (failure != null) {
            Result.failure(failure.exceptionOrNull() ?: RuntimeException("B2 delete failed."))
        } else {
            Result.success(Unit)
        }
    }

    private suspend fun performMediaStoreDelete(
        uris: List<Uri>,
        batchApproved: Boolean?
    ): DeletionResult? {
        val approved = batchApproved ?: mediaStoreDeleteLauncher.launch(uris)
        return if (approved) null else DeletionResult.Refused("User canceled.")
    }

    private fun failureCause(local: DeletionResult?, fallback: String): Throwable {
        return when (local) {
            is DeletionResult.Failed -> local.cause
            is DeletionResult.Refused -> RuntimeException(local.reason)
            else -> RuntimeException(fallback)
        }
    }

    private val DeletionResult.changedState: Boolean
        get() = this is DeletionResult.Success ||
                (this is DeletionResult.Failed && partialSuccess)
}
