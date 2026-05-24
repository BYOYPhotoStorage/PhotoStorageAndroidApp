package com.hriyaan.photostorage.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PrefsStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(creds: B2Credentials) {
        prefs.edit()
            .putString(KEY_ID, creds.keyId)
            .putString(APPLICATION_KEY, creds.applicationKey)
            .putString(BUCKET_NAME, creds.bucketName)
            .apply()
    }

    fun getCredentials(): B2Credentials? {
        val keyId = prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val appKey = prefs.getString(APPLICATION_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val bucket = prefs.getString(BUCKET_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        return B2Credentials(keyId, appKey, bucket)
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
    }

    fun hasCredentials(): Boolean {
        return !prefs.getString(KEY_ID, null).isNullOrBlank() &&
                !prefs.getString(APPLICATION_KEY, null).isNullOrBlank() &&
                !prefs.getString(BUCKET_NAME, null).isNullOrBlank()
    }

    fun isAutoUploadEnabled(): Boolean {
        return prefs.getBoolean(AUTO_UPLOAD_ENABLED, false)
    }

    fun setAutoUploadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_UPLOAD_ENABLED, enabled).apply()
    }

    fun isWifiOnly(): Boolean {
        return prefs.getBoolean(WIFI_ONLY, false)
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(WIFI_ONLY, enabled).apply()
    }

    fun getLastScanTimestamp(): Long {
        return prefs.getLong(LAST_SCAN_TIMESTAMP, 0L)
    }

    fun setLastScanTimestamp(timestamp: Long) {
        prefs.edit().putLong(LAST_SCAN_TIMESTAMP, timestamp).apply()
    }

    fun getGalleryViewMode(): String {
        val raw = prefs.getString(KEY_GALLERY_VIEW_MODE, null)
        return if (raw.isNullOrEmpty()) MODE_MERGED else raw
    }

    fun setGalleryViewMode(mode: String) {
        val normalized = if (mode in VALID_MODES) mode else MODE_MERGED
        prefs.edit().putString(KEY_GALLERY_VIEW_MODE, normalized).apply()
    }

    fun getLastSyncedIndexHash(): String? {
        return prefs.getString(KEY_LAST_SYNCED_INDEX_HASH, null)
    }

    fun setLastSyncedIndexHash(hash: String?) {
        val editor = prefs.edit()
        if (hash == null) {
            editor.remove(KEY_LAST_SYNCED_INDEX_HASH)
        } else {
            editor.putString(KEY_LAST_SYNCED_INDEX_HASH, hash)
        }
        editor.apply()
    }

    fun getLastIndexSyncAt(): Long? {
        val value = prefs.getLong(KEY_LAST_INDEX_SYNC_AT, -1L)
        return if (value == -1L) null else value
    }

    fun setLastIndexSyncAt(timestamp: Long?) {
        val editor = prefs.edit()
        if (timestamp == null) {
            editor.remove(KEY_LAST_INDEX_SYNC_AT)
        } else {
            editor.putLong(KEY_LAST_INDEX_SYNC_AT, timestamp)
        }
        editor.apply()
    }

    fun hasCompletedRecoveryFlow(): Boolean {
        return prefs.getBoolean(KEY_RECOVERY_FLOW_COMPLETED, false)
    }

    fun setRecoveryFlowCompleted(value: Boolean) {
        prefs.edit().putBoolean(KEY_RECOVERY_FLOW_COMPLETED, value).apply()
    }

    fun getFirstBackupSince(): Long {
        return prefs.getLong(KEY_FIRST_BACKUP_SINCE, -1L).let {
            if (it == -1L) {
                // Migrate from old string-based scope
                val scope = prefs.getString(KEY_FIRST_BACKUP_SCOPE, null)
                val since = if (scope == FIRST_BACKUP_SCOPE_ALL) 0L else System.currentTimeMillis()
                setFirstBackupSince(since)
                since
            } else {
                it
            }
        }
    }

    fun setFirstBackupSince(timestamp: Long) {
        prefs.edit()
            .putLong(KEY_FIRST_BACKUP_SINCE, timestamp)
            .putString(KEY_FIRST_BACKUP_SCOPE, if (timestamp == 0L) FIRST_BACKUP_SCOPE_ALL else FIRST_BACKUP_SCOPE_TODAY)
            .apply()
    }

    fun getFirstBackupScope(): String {
        val since = getFirstBackupSince()
        return if (since == 0L) FIRST_BACKUP_SCOPE_ALL else FIRST_BACKUP_SCOPE_TODAY
    }

    fun setFirstBackupScope(scope: String) {
        val normalized = if (scope in VALID_FIRST_BACKUP_SCOPES) scope else FIRST_BACKUP_SCOPE_TODAY
        val since = if (normalized == FIRST_BACKUP_SCOPE_ALL) 0L else System.currentTimeMillis()
        setFirstBackupSince(since)
    }

    fun hasCompletedFirstBackupFlow(): Boolean {
        return prefs.getBoolean(KEY_FIRST_BACKUP_FLOW_COMPLETED, false)
    }

    fun setFirstBackupFlowCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_FIRST_BACKUP_FLOW_COMPLETED, completed).apply()
    }

    fun getVideosEnabled(): Boolean {
        return prefs.getBoolean(KEY_VIDEOS_ENABLED, false)
    }

    fun setVideosEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEOS_ENABLED, enabled).apply()
    }

    fun getVideoQualityMode(): String {
        val raw = prefs.getString(KEY_VIDEO_QUALITY_MODE, null)
        return if (raw != null && raw in VALID_VIDEO_QUALITY_MODES) raw else VIDEO_QUALITY_DURATION_BASED
    }

    fun setVideoQualityMode(mode: String) {
        val normalized = if (mode in VALID_VIDEO_QUALITY_MODES) mode else VIDEO_QUALITY_DURATION_BASED
        prefs.edit().putString(KEY_VIDEO_QUALITY_MODE, normalized).apply()
    }

    fun getVideoDurationThresholdMinutes(): Int {
        return prefs.getInt(KEY_VIDEO_DURATION_THRESHOLD_MINUTES, 2)
    }

    fun setVideoDurationThresholdMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 60)
        prefs.edit().putInt(KEY_VIDEO_DURATION_THRESHOLD_MINUTES, clamped).apply()
    }

    fun getVideoTargetResolution(): String {
        val raw = prefs.getString(KEY_VIDEO_TARGET_RESOLUTION, null)
        return if (raw != null && raw in VALID_VIDEO_TARGET_RESOLUTIONS) raw else VIDEO_RESOLUTION_720P
    }

    fun setVideoTargetResolution(resolution: String) {
        val normalized = if (resolution in VALID_VIDEO_TARGET_RESOLUTIONS) resolution else VIDEO_RESOLUTION_720P
        prefs.edit().putString(KEY_VIDEO_TARGET_RESOLUTION, normalized).apply()
    }

    fun getUploadMode(): String {
        val raw = prefs.getString(KEY_UPLOAD_MODE, null)
        return if (raw != null && raw in VALID_UPLOAD_MODES) raw else UPLOAD_MODE_IMMEDIATE
    }

    fun setUploadMode(mode: String) {
        val normalized = if (mode in VALID_UPLOAD_MODES) mode else UPLOAD_MODE_IMMEDIATE
        prefs.edit().putString(KEY_UPLOAD_MODE, normalized).apply()
    }

    fun getLocalDeleteStrategy(): String {
        val raw = prefs.getString(KEY_LOCAL_DELETE_STRATEGY, null)
        return if (raw != null && raw in VALID_LOCAL_DELETE_STRATEGIES) raw else LOCAL_DELETE_NEVER
    }

    fun setLocalDeleteStrategy(strategy: String) {
        val normalized = if (strategy in VALID_LOCAL_DELETE_STRATEGIES) strategy else LOCAL_DELETE_NEVER
        prefs.edit().putString(KEY_LOCAL_DELETE_STRATEGY, normalized).apply()
    }

    fun getLocalDeleteDays(): Int {
        return prefs.getInt(KEY_LOCAL_DELETE_DAYS, 30)
    }

    fun setLocalDeleteDays(days: Int) {
        val clamped = days.coerceIn(1, 365)
        prefs.edit().putInt(KEY_LOCAL_DELETE_DAYS, clamped).apply()
    }

    fun getLocalDeleteCount(): Int {
        return prefs.getInt(KEY_LOCAL_DELETE_COUNT, 100)
    }

    fun setLocalDeleteCount(count: Int) {
        val clamped = count.coerceIn(1, 10_000)
        prefs.edit().putInt(KEY_LOCAL_DELETE_COUNT, clamped).apply()
    }

    fun getLocalDeleteSuppressUntil(): Long? {
        val value = prefs.getLong(KEY_LOCAL_DELETE_SUPPRESS_UNTIL, -1L)
        return if (value == -1L) null else value
    }

    fun setLocalDeleteSuppressUntil(timestamp: Long?) {
        val editor = prefs.edit()
        if (timestamp == null) {
            editor.remove(KEY_LOCAL_DELETE_SUPPRESS_UNTIL)
        } else {
            editor.putLong(KEY_LOCAL_DELETE_SUPPRESS_UNTIL, timestamp)
        }
        editor.apply()
    }

    fun getLastLocalDeleteRunAt(): Long? {
        val value = prefs.getLong(KEY_LAST_LOCAL_DELETE_RUN_AT, -1L)
        return if (value == -1L) null else value
    }

    fun setLastLocalDeleteRunAt(timestamp: Long?) {
        val editor = prefs.edit()
        if (timestamp == null) {
            editor.remove(KEY_LAST_LOCAL_DELETE_RUN_AT)
        } else {
            editor.putLong(KEY_LAST_LOCAL_DELETE_RUN_AT, timestamp)
        }
        editor.apply()
    }

    fun getLocalDeleteDismissStreak(): Int {
        return prefs.getInt(KEY_LOCAL_DELETE_DISMISS_STREAK, 0)
    }

    fun setLocalDeleteDismissStreak(count: Int) {
        val clamped = count.coerceIn(0, 999)
        prefs.edit().putInt(KEY_LOCAL_DELETE_DISMISS_STREAK, clamped).apply()
    }

    fun getEgressBytesMonth(): Long {
        return prefs.getLong(KEY_EGRESS_BYTES_MONTH, 0L)
    }

    fun setEgressBytesMonth(bytes: Long) {
        val clamped = bytes.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val anchor = getEgressMonthAnchor()
        if (sameMonth(anchor, now)) {
            prefs.edit().putLong(KEY_EGRESS_BYTES_MONTH, clamped).apply()
        } else {
            prefs.edit()
                .putLong(KEY_EGRESS_BYTES_MONTH, clamped)
                .putLong(KEY_EGRESS_MONTH_ANCHOR, now)
                .apply()
        }
    }

    fun getEgressMonthAnchor(): Long {
        return prefs.getLong(KEY_EGRESS_MONTH_ANCHOR, 0L)
    }

    fun setEgressMonthAnchor(timestamp: Long) {
        prefs.edit().putLong(KEY_EGRESS_MONTH_ANCHOR, timestamp).apply()
    }

    fun isDiagnosticsEnabled(): Boolean {
        if (!prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, false)) return false
        val startedAt = prefs.getLong(KEY_DIAGNOSTICS_STARTED_AT, 0L)
        if (startedAt == 0L) return false
        if (System.currentTimeMillis() - startedAt > DIAGNOSTICS_TIMEOUT_MS) {
            prefs.edit()
                .putBoolean(KEY_DIAGNOSTICS_ENABLED, false)
                .remove(KEY_DIAGNOSTICS_STARTED_AT)
                .apply()
            return false
        }
        return true
    }

    fun setDiagnosticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIAGNOSTICS_ENABLED, enabled).apply()
    }

    fun getDiagnosticsStartedAt(): Long? {
        val value = prefs.getLong(KEY_DIAGNOSTICS_STARTED_AT, 0L)
        return if (value == 0L) null else value
    }

    fun setDiagnosticsStartedAt(timestamp: Long?) {
        val editor = prefs.edit()
        if (timestamp == null) {
            editor.remove(KEY_DIAGNOSTICS_STARTED_AT)
        } else {
            editor.putLong(KEY_DIAGNOSTICS_STARTED_AT, timestamp)
        }
        editor.apply()
    }

    fun getSelectedBucketIds(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_BUCKET_IDS, null) ?: emptySet()
    }

    fun setSelectedBucketIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_BUCKET_IDS, ids).apply()
    }

    fun registerOnChangedListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnChangedListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun sameMonth(a: Long, b: Long): Boolean {
        val calA = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val calB = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return calA.get(java.util.Calendar.YEAR) == calB.get(java.util.Calendar.YEAR) &&
            calA.get(java.util.Calendar.MONTH) == calB.get(java.util.Calendar.MONTH)
    }

    companion object {
        private const val PREFS_FILE = "b2_credentials"
        private const val KEY_ID = "key_id"
        private const val APPLICATION_KEY = "application_key"
        private const val BUCKET_NAME = "bucket_name"
        private const val AUTO_UPLOAD_ENABLED = "auto_upload_enabled"
        private const val WIFI_ONLY = "wifi_only_uploads"
        private const val LAST_SCAN_TIMESTAMP = "last_scan_timestamp"
        private const val KEY_GALLERY_VIEW_MODE = "gallery_view_mode"
        private const val KEY_LAST_SYNCED_INDEX_HASH = "last_synced_index_hash"
        private const val KEY_LAST_INDEX_SYNC_AT = "last_index_sync_at"
        private const val KEY_RECOVERY_FLOW_COMPLETED = "recovery_flow_completed"

        private const val KEY_FIRST_BACKUP_SCOPE = "first_backup_scope"
        private const val KEY_FIRST_BACKUP_SINCE = "first_backup_since"
        private const val KEY_FIRST_BACKUP_FLOW_COMPLETED = "first_backup_flow_completed"
        private const val KEY_VIDEOS_ENABLED = "videos_enabled"
        private const val KEY_VIDEO_QUALITY_MODE = "video_quality_mode"
        private const val KEY_VIDEO_DURATION_THRESHOLD_MINUTES = "video_duration_threshold_minutes"
        private const val KEY_VIDEO_TARGET_RESOLUTION = "video_target_resolution"
        private const val KEY_UPLOAD_MODE = "upload_mode"
        private const val KEY_LOCAL_DELETE_STRATEGY = "local_delete_strategy"
        private const val KEY_LOCAL_DELETE_DAYS = "local_delete_days"
        private const val KEY_LOCAL_DELETE_COUNT = "local_delete_count"
        private const val KEY_LOCAL_DELETE_SUPPRESS_UNTIL = "local_delete_suppress_until"
        private const val KEY_LAST_LOCAL_DELETE_RUN_AT = "last_local_delete_run_at"
        private const val KEY_LOCAL_DELETE_DISMISS_STREAK = "local_delete_dismiss_streak"
        private const val KEY_EGRESS_BYTES_MONTH = "cost_dashboard_egress_bytes_month"
        private const val KEY_EGRESS_MONTH_ANCHOR = "cost_dashboard_egress_month_anchor"
        private const val KEY_DIAGNOSTICS_ENABLED = "diagnostics_enabled"
        private const val KEY_DIAGNOSTICS_STARTED_AT = "diagnostics_started_at"
        private const val KEY_SELECTED_BUCKET_IDS = "selected_bucket_ids"
        private const val DIAGNOSTICS_TIMEOUT_MS = 15L * 60L * 1000L

        private const val MODE_LOCAL = "local"
        private const val MODE_CLOUD = "cloud"
        private const val MODE_MERGED = "merged"
        private val VALID_MODES = setOf(MODE_LOCAL, MODE_CLOUD, MODE_MERGED)

        private const val FIRST_BACKUP_SCOPE_TODAY = "today"
        private const val FIRST_BACKUP_SCOPE_ALL = "all"
        private val VALID_FIRST_BACKUP_SCOPES = setOf(FIRST_BACKUP_SCOPE_TODAY, FIRST_BACKUP_SCOPE_ALL)

        private const val VIDEO_QUALITY_ORIGINAL = "original"
        private const val VIDEO_QUALITY_COMPRESSED = "compressed"
        private const val VIDEO_QUALITY_DURATION_BASED = "duration_based"
        private val VALID_VIDEO_QUALITY_MODES =
            setOf(VIDEO_QUALITY_ORIGINAL, VIDEO_QUALITY_COMPRESSED, VIDEO_QUALITY_DURATION_BASED)

        private const val VIDEO_RESOLUTION_720P = "720p"
        private const val VIDEO_RESOLUTION_1080P = "1080p"
        private val VALID_VIDEO_TARGET_RESOLUTIONS =
            setOf(VIDEO_RESOLUTION_720P, VIDEO_RESOLUTION_1080P)

        private const val UPLOAD_MODE_IMMEDIATE = "immediate"
        private const val UPLOAD_MODE_SCHEDULED = "scheduled"
        private const val UPLOAD_MODE_HYBRID = "hybrid"
        private val VALID_UPLOAD_MODES =
            setOf(UPLOAD_MODE_IMMEDIATE, UPLOAD_MODE_SCHEDULED, UPLOAD_MODE_HYBRID)

        private const val LOCAL_DELETE_NEVER = "never"
        private const val LOCAL_DELETE_IMMEDIATE = "immediate"
        private const val LOCAL_DELETE_AFTER_DAYS = "after_days"
        private const val LOCAL_DELETE_AFTER_COUNT = "after_count"
        private val VALID_LOCAL_DELETE_STRATEGIES = setOf(
            LOCAL_DELETE_NEVER,
            LOCAL_DELETE_IMMEDIATE,
            LOCAL_DELETE_AFTER_DAYS,
            LOCAL_DELETE_AFTER_COUNT
        )
    }
}
