package com.hriyaan.photostorage.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.cost.B2Pricing
import com.hriyaan.photostorage.cost.CostDashboardService
import com.hriyaan.photostorage.cost.CostSnapshot
import com.hriyaan.photostorage.cost.YearBreakdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.CharacterIterator
import java.text.StringCharacterIterator

class CostDashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cost_dashboard)
        title = getString(R.string.cost_dashboard_title)

        val app = application as PhotoBackupApp
        val service = CostDashboardService(app.uploadDatabase.dao, app.prefsStore)
        lifecycleScope.launch {
            val snap = service.computeSnapshot()
            render(snap)
        }
    }

    private fun render(snap: CostSnapshot) {
        findViewById<TextView>(R.id.header_total).text = getString(
            R.string.cost_header_total,
            (snap.photoCount + snap.videoCount).format(),
            formatBytes(snap.totalBytes)
        )
        findViewById<TextView>(R.id.header_cost).text = getString(
            R.string.cost_header_cost,
            formatUsd(snap.monthlyStorageCostUsd)
        )

        val videoBytes = snap.totalBytes - (snap.totalBytes - byteSizeOfVideos(snap))
        val photoBytes = snap.totalBytes - videoBytes

        findViewById<TextView>(R.id.row_photos).text = getString(
            R.string.cost_row_photos,
            snap.photoCount.format(),
            formatBytes(photoBytes),
            formatUsd(B2Pricing.monthlyStorageCostUsd(photoBytes))
        )
        findViewById<TextView>(R.id.row_videos).text = getString(
            R.string.cost_row_videos,
            snap.videoCount.format(),
            formatBytes(videoBytes),
            formatUsd(B2Pricing.monthlyStorageCostUsd(videoBytes))
        )

        findViewById<TextView>(R.id.row_pending).text = getString(
            R.string.cost_pending,
            snap.pendingCount.format(),
            formatBytes(snap.pendingBytes)
        )
        findViewById<TextView>(R.id.row_failed).text = getString(
            R.string.cost_failed,
            snap.permanentlyFailedCount.format()
        )

        val byYearContainer = findViewById<LinearLayout>(R.id.by_year_container)
        val headerByYear = findViewById<TextView>(R.id.header_by_year)
        val dividerByYear = findViewById<View>(R.id.divider_by_year)
        if (snap.byYear.isNotEmpty()) {
            headerByYear.visibility = View.VISIBLE
            byYearContainer.visibility = View.VISIBLE
            dividerByYear.visibility = View.VISIBLE
            byYearContainer.removeAllViews()
            for (year in snap.byYear) {
                val row = TextView(this)
                row.textSize = 15f
                row.setPadding(0, 4, 0, 4)
                row.text = getString(
                    R.string.cost_year_row,
                    year.year,
                    formatBytes(year.bytes),
                    year.photoCount.format(),
                    year.videoCount.format()
                )
                byYearContainer.addView(row)
            }
        } else {
            headerByYear.visibility = View.GONE
            byYearContainer.visibility = View.GONE
            dividerByYear.visibility = View.GONE
        }

        val rowEgress = findViewById<TextView>(R.id.row_egress)
        val dividerEgress = findViewById<View>(R.id.divider_egress)
        if (snap.monthlyEgressCostUsd != null) {
            rowEgress.visibility = View.VISIBLE
            dividerEgress.visibility = View.VISIBLE
            rowEgress.text = getString(
                R.string.cost_egress,
                formatUsd(snap.monthlyEgressCostUsd),
                formatBytes(snap.totalBytes)
            )
        } else {
            rowEgress.visibility = View.GONE
            dividerEgress.visibility = View.GONE
        }

        findViewById<TextView>(R.id.footnote).text = getString(
            R.string.cost_footnote, B2Pricing.LAST_UPDATED
        )
    }

    private fun byteSizeOfVideos(snap: CostSnapshot): Long {
        return snap.byYear.sumOf { it.bytes * it.videoCount / (it.photoCount + it.videoCount).coerceAtLeast(1) }
    }

    private fun formatBytes(bytes: Long): String {
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

    private fun formatUsd(value: Double): String {
        return "$%.2f".format(java.util.Locale.US, value)
    }

    private fun Int.format(): String = java.text.NumberFormat.getInstance().format(this)
}
