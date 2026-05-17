package com.hriyaan.photostorage.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hriyaan.photostorage.PhotoBackupApp

class DeleteIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PhotoBackupApp
        val prefs = app.prefsStore
        val streak = prefs.getLocalDeleteDismissStreak() + 1
        prefs.setLocalDeleteDismissStreak(streak)
        if (streak >= 3) {
            prefs.setLocalDeleteSuppressUntil(System.currentTimeMillis() + 7 * 86_400_000L)
            prefs.setLocalDeleteDismissStreak(0)
        }
    }
}
