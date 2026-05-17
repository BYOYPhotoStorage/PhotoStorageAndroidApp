package com.hriyaan.photostorage.cost

import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.UploadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class CostDashboardService(
    private val uploadDao: UploadDao,
    private val prefsStore: PrefsStore
) {

    suspend fun computeSnapshot(): CostSnapshot = withContext(Dispatchers.IO) {
        val photoCount = uploadDao.countByMediaType(UploadDao.MEDIA_TYPE_PHOTO)
        val videoCount = uploadDao.countByMediaType(UploadDao.MEDIA_TYPE_VIDEO)
        val photoBytes = uploadDao.sumSizeByMediaType(UploadDao.MEDIA_TYPE_PHOTO)
        val videoBytes = uploadDao.sumSizeByMediaType(UploadDao.MEDIA_TYPE_VIDEO)
        val totalBytes = photoBytes + videoBytes

        val pending = uploadDao.getPendingQueue()
        val pendingCount = pending.size
        val pendingBytes = pending.sumOf { it.size }

        val permanentlyFailedCount = uploadDao.getAll().count { it.status == UploadDao.STATUS_PERMANENTLY_FAILED }

        val egressBytes = prefsStore.getEgressBytesMonth()
        val byYear = computeByYear(uploadDao)

        CostSnapshot(
            photoCount = photoCount,
            videoCount = videoCount,
            totalBytes = totalBytes,
            pendingCount = pendingCount,
            pendingBytes = pendingBytes,
            permanentlyFailedCount = permanentlyFailedCount,
            monthlyStorageCostUsd = B2Pricing.monthlyStorageCostUsd(totalBytes),
            monthlyEgressCostUsd = if (egressBytes > 0L) B2Pricing.egressCostUsd(egressBytes) else null,
            byYear = byYear
        )
    }

    private fun computeByYear(dao: UploadDao): List<YearBreakdown> {
        val rows = dao.getCloudView()
        return rows
            .groupBy { yearOf(it.dateTaken) }
            .map { (year, rs) ->
                YearBreakdown(
                    year = year,
                    bytes = rs.sumOf { it.size },
                    photoCount = rs.count { it.mediaType == UploadDao.MEDIA_TYPE_PHOTO },
                    videoCount = rs.count { it.mediaType == UploadDao.MEDIA_TYPE_VIDEO }
                )
            }
            .sortedByDescending { it.year }
            .take(5)
    }

    private fun yearOf(dateTakenMs: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = dateTakenMs }
        return cal.get(Calendar.YEAR)
    }
}

data class CostSnapshot(
    val photoCount: Int,
    val videoCount: Int,
    val totalBytes: Long,
    val pendingCount: Int,
    val pendingBytes: Long,
    val permanentlyFailedCount: Int,
    val monthlyStorageCostUsd: Double,
    val monthlyEgressCostUsd: Double?,
    val byYear: List<YearBreakdown>
)

data class YearBreakdown(val year: Int, val bytes: Long, val photoCount: Int, val videoCount: Int)
