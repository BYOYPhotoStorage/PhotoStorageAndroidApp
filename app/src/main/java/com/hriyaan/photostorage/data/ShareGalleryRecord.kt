package com.hriyaan.photostorage.data

data class ShareGalleryRecord(
    val id: Long = 0L,
    val htmlB2Path: String,
    val url: String,
    val itemCount: Int,
    val createdAt: Long,
    val expiresAt: Long
)
