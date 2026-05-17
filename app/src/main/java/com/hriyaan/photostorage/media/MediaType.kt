package com.hriyaan.photostorage.media

enum class MediaType {
    PHOTO, VIDEO;

    fun toDbValue(): String = when (this) {
        PHOTO -> "photo"
        VIDEO -> "video"
    }

    companion object {
        fun fromDbValue(value: String): MediaType =
            if (value == "video") VIDEO else PHOTO
    }
}
