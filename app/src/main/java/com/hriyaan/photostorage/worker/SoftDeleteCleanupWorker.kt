package com.hriyaan.photostorage.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hriyaan.photostorage.PhotoBackupApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SoftDeleteCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PhotoBackupApp
        val dao = app.uploadDatabase.dao
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)

        val stale = dao.getSoftDeletedOlderThan(cutoff)
        for (record in stale) {
            dao.hardDelete(record.id)
        }
        Result.success()
    }
}
