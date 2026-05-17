package com.hriyaan.photostorage

import android.app.Application
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDatabase
import com.hriyaan.photostorage.notification.UploadNotificationManager

class PhotoBackupApp : Application() {

    lateinit var prefsStore: PrefsStore
        private set

    lateinit var uploadDatabase: UploadDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        prefsStore = PrefsStore(this)
        uploadDatabase = UploadDatabase(this)
        UploadNotificationManager(this).ensureChannels()
    }
}
