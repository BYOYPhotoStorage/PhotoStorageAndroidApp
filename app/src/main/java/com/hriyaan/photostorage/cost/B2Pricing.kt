package com.hriyaan.photostorage.cost

object B2Pricing {
    const val STORAGE_PER_GB_PER_MONTH_USD: Double = 0.006
    const val EGRESS_PER_GB_USD: Double = 0.010
    const val LAST_UPDATED: String = "2026-05-17"

    fun monthlyStorageCostUsd(bytes: Long): Double {
        val gb = bytes / 1_000_000_000.0
        return gb * STORAGE_PER_GB_PER_MONTH_USD
    }

    fun egressCostUsd(bytes: Long): Double {
        val gb = bytes / 1_000_000_000.0
        return gb * EGRESS_PER_GB_USD
    }
}
