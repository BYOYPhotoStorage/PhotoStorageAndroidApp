package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hriyaan.photostorage.data.PrefsStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

object NightlyScanScheduler {

    fun schedule(context: Context) {
        val prefsStore = PrefsStore(context)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(
                if (prefsStore.isWifiOnly()) NetworkType.UNMETERED
                else NetworkType.CONNECTED
            )
            .build()

        val workRequest = PeriodicWorkRequestBuilder<NightlyScanWorker>(
            repeatInterval = 24, TimeUnit.HOURS,
            flexTimeInterval = 1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(calculateDelayUntil2am(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "nightly_scan",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("nightly_scan")
    }

    private fun calculateDelayUntil2am(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
