package com.hriyaan.photostorage.share

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ShareGalleryHtmlGenerator(private val context: Context) {

    fun generate(items: List<GalleryShareItem>): String {
        val template = context.assets.open(TEMPLATE_PATH).bufferedReader().use { it.readText() }
        val json = items.toJsonArray().toString()
        return template.replace(PLACEHOLDER, json)
    }

    private fun List<GalleryShareItem>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { array.put(it.toJson()) }
        return array
    }

    private fun GalleryShareItem.toJson(): JSONObject {
        return JSONObject().apply {
            put("filename", filename)
            put("mediaType", mediaType)
            put("thumbnailUrl", thumbnailUrl)
            put("fullUrl", fullUrl)
        }
    }

    companion object {
        private const val TEMPLATE_PATH = "share_gallery_template.html"
        private const val PLACEHOLDER = "{{GALLERY_DATA}}"
    }
}

data class GalleryShareItem(
    val filename: String,
    val mediaType: String,
    val thumbnailUrl: String,
    val fullUrl: String
)
