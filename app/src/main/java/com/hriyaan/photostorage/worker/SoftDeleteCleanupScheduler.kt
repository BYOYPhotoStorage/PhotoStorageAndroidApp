package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SoftDeleteCleanupScheduler {
    private const val WORK_NAME = "soft_delete_cleanup"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SoftDeleteCleanupWorker>(
            1, TimeUnit.DAYS,
            6, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
