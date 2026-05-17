package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object LocalDeleteScheduler {
    private const val PERIODIC_NAME = "local_delete_daily"
    private const val ONE_SHOT_NAME = "local_delete_now"

    fun schedule(context: Context) {
        val initialDelayMs = msUntilNext(localHour = 19, localMinute = 0)
        val request = PeriodicWorkRequestBuilder<LocalDeleteWorker>(
            repeatInterval = 1, repeatIntervalTimeUnit = TimeUnit.DAYS,
            flexTimeInterval = 1, flexTimeIntervalUnit = TimeUnit.HOURS
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
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
        val request = OneTimeWorkRequestBuilder<LocalDeleteWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
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
