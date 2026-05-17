package com.hriyaan.photostorage.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.ui.GalleryActivity
import com.hriyaan.photostorage.ui.OnboardingActivity

@SuppressLint("MissingPermission", "NotificationPermission")
class UploadNotificationManager(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        NotificationChannels.create(context)
    }

    fun buildForegroundNotification(pendingCount: Int): Notification {
        val text = if (pendingCount == 0) "All caught up" else "$pendingCount pending"
        return NotificationCompat.Builder(context, NotificationChannels.CHANNEL_SERVICE)
            .setContentTitle("Photo backup is active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun showProgressNotification(current: Int, total: Int) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_EVENTS)
            .setContentTitle("Uploading photos")
            .setContentText("$current / $total")
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setProgress(total, current, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ID_PROGRESS, notification)
    }

    fun showCompletionNotification(count: Int) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_EVENTS)
            .setContentTitle("Photos backed up")
            .setContentText("$count photos uploaded")
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ID_COMPLETION, notification)
    }

    fun showPermanentFailureNotification(failedCount: Int) {
        val intent = Intent(context, GalleryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_EVENTS)
            .setContentTitle("Backup failed")
            .setContentText("$failedCount photos could not be backed up. Tap to retry.")
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ID_PERMANENT_FAILURE, notification)
    }

    fun showAuthFailureNotification() {
        val intent = Intent(context, OnboardingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_EVENTS)
            .setContentTitle("Backup credentials expired")
            .setContentText("Tap to reconnect your Backblaze account.")
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ID_AUTH_FAILURE, notification)
    }

    fun cancelProgressNotification() {
        notificationManager.cancel(ID_PROGRESS)
    }

    companion object {
        const val ID_FOREGROUND = 1
        const val ID_PROGRESS = 2
        const val ID_COMPLETION = 3
        const val ID_PERMANENT_FAILURE = 4
        const val ID_AUTH_FAILURE = 5
    }
}
