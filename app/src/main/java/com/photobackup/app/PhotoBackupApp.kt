package com.photobackup.app

import android.app.Application
import com.photobackup.app.data.PrefsStore
import com.photobackup.app.data.UploadDatabase

class PhotoBackupApp : Application() {

    lateinit var prefsStore: PrefsStore
        private set

    lateinit var uploadDatabase: UploadDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        prefsStore = PrefsStore(this)
        uploadDatabase = UploadDatabase(this)
    }
}
