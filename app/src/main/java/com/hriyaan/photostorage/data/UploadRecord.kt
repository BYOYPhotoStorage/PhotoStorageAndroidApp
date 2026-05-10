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
    val uploadedAt: Long?
)
