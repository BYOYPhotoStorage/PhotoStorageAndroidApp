package com.hriyaan.photostorage.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.service.UploadForegroundService
import com.hriyaan.photostorage.worker.NightlyScanScheduler

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefsStore = PrefsStore(context)
        if (prefsStore.isAutoUploadEnabled()) {
            UploadForegroundService.start(context)
            NightlyScanScheduler.schedule(context)
        }
    }
}
