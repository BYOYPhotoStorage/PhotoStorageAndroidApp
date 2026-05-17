package com.hriyaan.photostorage.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hriyaan.photostorage.data.PrefsStore
import java.util.Calendar

class UploadModeGate(
    private val context: Context,
    private val prefsStore: PrefsStore
) {

    sealed class Decision {
        object UploadNow : Decision()
        data class Defer(val reason: String, val until: Long?) : Decision()
    }

    fun decide(now: Long = System.currentTimeMillis()): Decision {
        val mode = prefsStore.getUploadMode()
        val wifiOnly = prefsStore.isWifiOnly()
        val unmetered = isUnmetered()
        return when (mode) {
            MODE_IMMEDIATE -> {
                if (wifiOnly && !unmetered) Decision.Defer(REASON_WAITING_WIFI, null)
                else Decision.UploadNow
            }
            MODE_SCHEDULED -> Decision.Defer(REASON_SCHEDULED, nextNightlyWindow(now))
            MODE_HYBRID -> {
                if (unmetered) Decision.UploadNow
                else Decision.Defer(REASON_WAITING_WIFI, null)
            }
            else -> Decision.UploadNow
        }
    }

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun nextNightlyWindow(now: Long): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now) target.add(Calendar.DAY_OF_MONTH, 1)
        return target.timeInMillis
    }

    companion object {
        const val MODE_IMMEDIATE = "immediate"
        const val MODE_SCHEDULED = "scheduled"
        const val MODE_HYBRID = "hybrid"
        const val REASON_WAITING_WIFI = "Waiting for Wi-Fi"
        const val REASON_SCHEDULED = "Scheduled batch only"
    }
}
