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

    companion object {
        private const val PREFS_FILE = "b2_credentials"
        private const val KEY_ID = "key_id"
        private const val APPLICATION_KEY = "application_key"
        private const val BUCKET_NAME = "bucket_name"
        private const val AUTO_UPLOAD_ENABLED = "auto_upload_enabled"
        private const val WIFI_ONLY = "wifi_only_uploads"
        private const val LAST_SCAN_TIMESTAMP = "last_scan_timestamp"
    }
}
