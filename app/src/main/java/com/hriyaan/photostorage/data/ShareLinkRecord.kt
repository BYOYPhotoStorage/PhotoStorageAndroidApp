package com.hriyaan.photostorage.data

data class ShareLinkRecord(
    val id: Long = 0L,
    val uploadId: Long,
    val photoB2Path: String,
    val url: String,
    val createdAt: Long,
    val expiresAt: Long
)
