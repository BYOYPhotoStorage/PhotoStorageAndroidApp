package com.hriyaan.photostorage.gallery

enum class GalleryViewMode(val key: String) {
    LOCAL("local"),
    CLOUD("cloud"),
    MERGED("merged");

    companion object {
        fun fromKey(key: String?): GalleryViewMode =
            entries.firstOrNull { it.key == key } ?: MERGED
    }
}
