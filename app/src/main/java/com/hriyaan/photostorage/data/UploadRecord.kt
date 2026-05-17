package com.hriyaan.photostorage.data

data class UploadRecord(
    val id: Long = 0L,
    val localUri: String,
    val filename: String,
    val size: Long,
    val dateTaken: Long,
    val photoB2Path: String?,
    val thumbnailB2Path: String?,
    val status: String,
    val uploadedAt: Long?,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val sha256: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val localPresent: Boolean = true,
    val cloudDeletedAt: Long? = null,
    val mediaType: String = "photo",
    val originalPathB2: String? = null,
    val pendingLocalDelete: Boolean = false,
    val compressed: Boolean = false
)
