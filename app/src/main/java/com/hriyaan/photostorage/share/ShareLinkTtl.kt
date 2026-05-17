package com.hriyaan.photostorage.share

import com.hriyaan.photostorage.R

enum class ShareLinkTtl(val seconds: Long, val labelRes: Int) {
    ONE_HOUR(3_600L, R.string.share_ttl_1h),
    ONE_DAY(86_400L, R.string.share_ttl_24h),
    ONE_WEEK(604_800L, R.string.share_ttl_7d);

    fun expiryFromNow(now: Long = System.currentTimeMillis()): Long = now + seconds * 1_000L
}
