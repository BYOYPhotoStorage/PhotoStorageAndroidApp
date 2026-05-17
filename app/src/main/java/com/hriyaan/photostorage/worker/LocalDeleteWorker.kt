package com.hriyaan.photostorage.worker

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.notification.NotificationChannels
import com.hriyaan.photostorage.receiver.DeleteIntentReceiver
import com.hriyaan.photostorage.ui.LocalDeleteReviewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.CharacterIterator
import java.text.StringCharacterIterator

@SuppressLint("MissingPermission", "NotificationPermission")
class LocalDeleteWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val NOTIFICATION_ID = 5001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PhotoBackupApp
        val prefs = app.prefsStore
        val dao = app.uploadDatabase.dao
        val now = System.currentTimeMillis()

        prefs.setLastLocalDeleteRunAt(now)

        val strategy = prefs.getLocalDeleteStrategy()
        if (strategy == "never") return@withContext Result.success()

        val suppressUntil = prefs.getLocalDeleteSuppressUntil()
        if (suppressUntil != null && suppressUntil > now) return@withContext Result.success()

        val candidates = collectCandidates(strategy, prefs, dao, now)
        if (candidates.isEmpty()) return@withContext Result.success()

        dao.markPendingLocalDelete(candidates.map { it.id })

        showDailyNotification(candidates.size, candidates.sumOf { it.size })

        Result.success()
    }

    private fun collectCandidates(
        strategy: String,
        prefs: PrefsStore,
        dao: UploadDao,
        now: Long
    ): List<UploadRecord> = when (strategy) {
        "immediate" -> dao.getEligibleForLocalDelete(olderThanUploadedAt = now)
        "after_days" -> {
            val cutoff = now - prefs.getLocalDeleteDays() * 86_400_000L
            dao.getEligibleForLocalDelete(olderThanUploadedAt = cutoff)
        }
        "after_count" -> {
            val anchor = prefs.getLastLocalDeleteRunAt() ?: 0L
            val newSince = dao.countUploadsSince(anchor)
            if (newSince < prefs.getLocalDeleteCount()) emptyList()
            else dao.getOldestUploaded(limit = newSince)
        }
        else -> emptyList()
    }

    private fun showDailyNotification(count: Int, totalBytes: Long) {
        val context = applicationContext

        val contentIntent = Intent(context, LocalDeleteReviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = Intent(context, DeleteIntentReceiver::class.java)
        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sizeStr = humanReadableByteCountBin(totalBytes)
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_EVENTS)
            .setContentTitle(context.getString(R.string.local_delete_notif_title))
            .setContentText(context.getString(R.string.local_delete_notif_body, count, sizeStr))
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun humanReadableByteCountBin(bytes: Long): String {
        val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(bytes)
        if (absB < 1024) return "$bytes B"
        var value = absB
        val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
        var i = 40
        while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
            value = value shr 10
            ci.next()
            i -= 10
        }
        value *= if (bytes < 0) -1 else 1
        return String.format(java.util.Locale.US, "%.1f %ciB", value / 1024.0, ci.current())
    }
}
