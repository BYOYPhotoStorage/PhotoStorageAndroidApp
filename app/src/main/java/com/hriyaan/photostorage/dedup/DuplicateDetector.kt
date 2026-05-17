package com.hriyaan.photostorage.dedup

import android.net.Uri
import com.hriyaan.photostorage.data.MediaStorePhoto
import com.hriyaan.photostorage.data.UploadDao

sealed class DuplicateResult {
    object NotDuplicate : DuplicateResult()
    data class Duplicate(val reason: String) : DuplicateResult()
}

class DuplicateDetector(private val uploadDao: UploadDao) {

    suspend fun isDuplicate(photo: MediaStorePhoto): DuplicateResult {
        // TODO: Implemented in Task 15
        return DuplicateResult.NotDuplicate
    }

    suspend fun computeSha256(uri: Uri): String {
        // TODO: Implemented in Task 15
        return ""
    }
}
