package com.hriyaan.photostorage.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val CHANNEL_SERVICE = "upload_service"
    const val CHANNEL_EVENTS = "upload_events"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Photo backup",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }

        val eventsChannel = NotificationChannel(
            CHANNEL_EVENTS,
            "Backup events",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        manager.createNotificationChannels(listOf(serviceChannel, eventsChannel))
    }
}
