package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hriyaan.photostorage.PhotoBackupApp
import java.util.Calendar
import java.util.concurrent.TimeUnit

object IndexSyncScheduler {
    private const val PERIODIC_NAME = "index_sync_nightly"
    private const val ONE_SHOT_NAME = "index_sync_now"

    fun schedule(context: Context) {
        val initialDelayMs = msUntilNext(localHour = 3, localMinute = 0)
        val request = PeriodicWorkRequestBuilder<IndexSyncWorker>(
            1, TimeUnit.DAYS,
            1, TimeUnit.HOURS
        )
            .setConstraints(buildConstraints(context))
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<IndexSyncWorker>()
            .setConstraints(buildConstraints(context))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun buildConstraints(context: Context): Constraints {
        val prefs = (context.applicationContext as PhotoBackupApp).prefsStore
        val networkType = if (prefs.isWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }

    private fun msUntilNext(localHour: Int, localMinute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, localHour)
            set(Calendar.MINUTE, localMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
