package com.hriyaan.photostorage.b2

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object S3KeyBuilder {
    private val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy/MM/dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun photoKey(filename: String, dateTakenMs: Long): String =
        "photos/${formatDate(dateTakenMs)}/$filename"

    fun thumbnailKey(filename: String, dateTakenMs: Long): String {
        val basename = filename.substringBeforeLast('.', filename)
        return "thumbnails/${formatDate(dateTakenMs)}/$basename.webp"
    }

    private fun formatDate(ms: Long): String = dateFormat.format(Date(ms))
}
