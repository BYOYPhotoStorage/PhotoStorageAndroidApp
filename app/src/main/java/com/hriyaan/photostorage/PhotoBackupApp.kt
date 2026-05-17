package com.hriyaan.photostorage

import android.app.Application
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDatabase
import com.hriyaan.photostorage.gallery.GalleryRepository
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.worker.IndexSyncScheduler
import com.hriyaan.photostorage.worker.SoftDeleteCleanupScheduler

class PhotoBackupApp : Application() {

    lateinit var prefsStore: PrefsStore
        private set

    lateinit var uploadDatabase: UploadDatabase
        private set

    lateinit var galleryRepository: GalleryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefsStore = PrefsStore(this)
        uploadDatabase = UploadDatabase(this)
        galleryRepository = GalleryRepository(this, uploadDatabase.dao, prefsStore)
        UploadNotificationManager(this).ensureChannels()
        IndexSyncScheduler.schedule(this)
        SoftDeleteCleanupScheduler.schedule(this)
    }
}
