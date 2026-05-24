package com.hriyaan.photostorage.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import aws.smithy.kotlin.runtime.content.ByteStream
import com.google.android.material.appbar.MaterialToolbar
import com.hriyaan.photostorage.MainActivity
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.data.FileLogger
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.recovery.IndexRecoveryActivity
import com.hriyaan.photostorage.service.UploadForegroundService
import com.hriyaan.photostorage.worker.IndexSyncScheduler
import com.hriyaan.photostorage.worker.LocalDeleteScheduler
import com.hriyaan.photostorage.worker.NightlyScanScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private val app: PhotoBackupApp get() = application as PhotoBackupApp
    private val prefs: PrefsStore get() = app.prefsStore

    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val switch = findViewById<Switch>(R.id.switch_videos_enabled)
        if (!granted) {
            switch.isChecked = false
            prefs.setVideosEnabled(false)
            Toast.makeText(this, R.string.settings_videos_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        bindBackupSection()
        bindBackupFoldersSection()
        bindVideosSection()
        bindStorageManagementSection()
        bindStorageCostSection()
        bindIndexSection()
        bindSharingSection()
        bindAccountSection()
        bindDiagnosticsSection()
        bindAboutSection()

        refreshAll()
    }

    private fun refreshAll() {
        val autoUpload = prefs.isAutoUploadEnabled()
        findViewById<Switch>(R.id.switch_auto_upload).isChecked = autoUpload
        findViewById<Switch>(R.id.switch_wifi_only).isChecked = prefs.isWifiOnly()
        updateTimingValue()
        updateBackupFoldersValue()
        updateDeleteStrategyValue()
        updateVideoQualityValue()
        updateVideoResolutionValue()
        updateVideoSectionVisibility()
        updateStorageConditionalVisibility()
        updateLastIndexSync()
        updateShareLinksCount()
        updateDiagnosticsStatus()
        updateAboutSection()
        setSectionEnabled(R.id.row_timing, autoUpload)
        setSectionEnabled(R.id.row_wifi_only, autoUpload)
        setSectionEnabled(R.id.row_backup_folders, autoUpload)
        setSectionEnabled(R.id.row_videos_enabled, autoUpload)
        setSectionEnabled(R.id.row_video_quality, autoUpload)
        setSectionEnabled(R.id.row_video_threshold, autoUpload)
        setSectionEnabled(R.id.row_video_resolution, autoUpload)
        setSectionEnabled(R.id.row_delete_strategy, autoUpload)
        setSectionEnabled(R.id.row_delete_days, autoUpload)
        setSectionEnabled(R.id.row_delete_count, autoUpload)
        setSectionEnabled(R.id.btn_review_deletions, autoUpload)
    }

    private fun setSectionEnabled(viewId: Int, enabled: Boolean) {
        findViewById<View>(viewId).alpha = if (enabled) 1.0f else 0.4f
    }

    private fun bindBackupSection() {
        findViewById<Switch>(R.id.switch_auto_upload).setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoUploadEnabled(isChecked)
            if (isChecked) {
                UploadForegroundService.start(this)
                NightlyScanScheduler.schedule(this)
            } else {
                UploadForegroundService.stop(this)
                NightlyScanScheduler.cancel(this)
            }
            refreshAll()
        }

        findViewById<Switch>(R.id.switch_wifi_only).setOnCheckedChangeListener { _, isChecked ->
            prefs.setWifiOnly(isChecked)
        }

        findViewById<LinearLayout>(R.id.row_timing).setOnClickListener {
            if (!prefs.isAutoUploadEnabled()) return@setOnClickListener
            showTimingPicker()
        }
    }

    private fun showTimingPicker() {
        val modes = arrayOf(
            getString(R.string.settings_timing_immediate),
            getString(R.string.settings_timing_scheduled),
            getString(R.string.settings_timing_hybrid)
        )
        val values = arrayOf("immediate", "scheduled", "hybrid")
        val current = prefs.getUploadMode()
        val checked = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_timing)
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                prefs.setUploadMode(values[which])
                updateTimingValue()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateTimingValue() {
        val value = when (prefs.getUploadMode()) {
            "scheduled" -> getString(R.string.settings_timing_scheduled)
            "hybrid" -> getString(R.string.settings_timing_hybrid)
            else -> getString(R.string.settings_timing_immediate)
        }
        findViewById<TextView>(R.id.timing_value).text = value
    }

    private fun bindBackupFoldersSection() {
        findViewById<LinearLayout>(R.id.row_backup_folders).setOnClickListener {
            if (!prefs.isAutoUploadEnabled()) return@setOnClickListener
            showFolderPickerDialog()
        }
    }

    private fun showFolderPickerDialog() {
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                com.hriyaan.photostorage.data.MediaStoreQuery(this@SettingsActivity).queryPhotoFolders()
            }
            if (folders.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "No folders found", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val selected = prefs.getSelectedBucketIds()
            val folderNames = folders.map { it.bucketName }.toTypedArray()
            val checked = folders.map { it.bucketId in selected }.toBooleanArray()
            val mutableChecked = checked.copyOf()

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_backup_folders_dialog_title)
                .setMultiChoiceItems(folderNames, mutableChecked) { _, which, isChecked ->
                    mutableChecked[which] = isChecked
                }
                .setPositiveButton(R.string.ok) { _, _ ->
                    val newSelection = folders
                        .filterIndexed { index, _ -> mutableChecked[index] }
                        .map { it.bucketId }
                        .toSet()
                    prefs.setSelectedBucketIds(newSelection)
                    updateBackupFoldersValue()
                    app.galleryRepository.invalidate()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateBackupFoldersValue() {
        val selected = prefs.getSelectedBucketIds()
        val textView = findViewById<TextView>(R.id.backup_folders_value)
        if (selected.isEmpty()) {
            textView.text = getString(R.string.settings_backup_folders_all)
        } else {
            lifecycleScope.launch {
                val total = withContext(Dispatchers.IO) {
                    com.hriyaan.photostorage.data.MediaStoreQuery(this@SettingsActivity)
                        .queryPhotoFolders().size
                }
                textView.text = getString(R.string.settings_backup_folders_count, selected.size, total)
            }
        }
    }

    private fun bindVideosSection() {
        findViewById<Switch>(R.id.switch_videos_enabled).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                videoPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
            }
            prefs.setVideosEnabled(isChecked)
            updateVideoSectionVisibility()
        }

        findViewById<LinearLayout>(R.id.row_video_quality).setOnClickListener {
            if (!prefs.isAutoUploadEnabled()) return@setOnClickListener
            showQualityPicker()
        }

        findViewById<LinearLayout>(R.id.row_video_resolution).setOnClickListener {
            if (!prefs.isAutoUploadEnabled()) return@setOnClickListener
            showResolutionPicker()
        }

        val seekBar = findViewById<SeekBar>(R.id.seekbar_threshold)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1
                prefs.setVideoDurationThresholdMinutes(minutes)
                findViewById<TextView>(R.id.threshold_value).text =
                    getString(R.string.settings_videos_threshold_units, minutes)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showQualityPicker() {
        val modes = arrayOf(
            getString(R.string.settings_videos_quality_original),
            getString(R.string.settings_videos_quality_compressed),
            getString(R.string.settings_videos_quality_duration_based)
        )
        val values = arrayOf("original", "compressed", "duration_based")
        val current = prefs.getVideoQualityMode()
        val checked = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_videos_quality)
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                prefs.setVideoQualityMode(values[which])
                updateVideoQualityValue()
                updateVideoSectionVisibility()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showResolutionPicker() {
        val options = arrayOf("720p", "1080p")
        val current = prefs.getVideoTargetResolution()
        val checked = options.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_videos_target_resolution)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                prefs.setVideoTargetResolution(options[which])
                updateVideoResolutionValue()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateVideoQualityValue() {
        val value = when (prefs.getVideoQualityMode()) {
            "original" -> getString(R.string.settings_videos_quality_original)
            "compressed" -> getString(R.string.settings_videos_quality_compressed)
            else -> getString(R.string.settings_videos_quality_duration_based)
        }
        findViewById<TextView>(R.id.video_quality_value).text = value
    }

    private fun updateVideoResolutionValue() {
        findViewById<TextView>(R.id.video_resolution_value).text = prefs.getVideoTargetResolution()
    }

    private fun updateVideoSectionVisibility() {
        val enabled = prefs.getVideosEnabled()
        findViewById<View>(R.id.row_video_quality).visibility = if (enabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.row_video_resolution).visibility = if (enabled) View.VISIBLE else View.GONE

        val isDurationBased = prefs.getVideoQualityMode() == "duration_based"
        findViewById<View>(R.id.row_video_threshold).visibility =
            if (enabled && isDurationBased) View.VISIBLE else View.GONE

        val minutes = prefs.getVideoDurationThresholdMinutes()
        findViewById<SeekBar>(R.id.seekbar_threshold).progress = minutes - 1
        findViewById<TextView>(R.id.threshold_value).text =
            getString(R.string.settings_videos_threshold_units, minutes)
    }

    private fun bindStorageManagementSection() {
        findViewById<LinearLayout>(R.id.row_delete_strategy).setOnClickListener {
            if (!prefs.isAutoUploadEnabled()) return@setOnClickListener
            showStrategyPicker()
        }

        findViewById<Button>(R.id.btn_review_deletions).setOnClickListener {
            if (!prefs.isAutoUploadEnabled()) return@setOnClickListener
            LocalDeleteScheduler.runNow(this)
            Toast.makeText(this, R.string.settings_storage_review_now, Toast.LENGTH_SHORT).show()
        }

        val daysEdit = findViewById<EditText>(R.id.edit_delete_days)
        daysEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = daysEdit.text.toString().toIntOrNull()?.coerceIn(1, 365) ?: 30
                prefs.setLocalDeleteDays(value)
                daysEdit.setText(value.toString())
            }
        }

        val countEdit = findViewById<EditText>(R.id.edit_delete_count)
        countEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = countEdit.text.toString().toIntOrNull()?.coerceIn(1, 10_000) ?: 100
                prefs.setLocalDeleteCount(value)
                countEdit.setText(value.toString())
            }
        }
    }

    private fun showStrategyPicker() {
        val modes = arrayOf(
            getString(R.string.settings_storage_strategy_never),
            getString(R.string.settings_storage_strategy_immediate),
            getString(R.string.settings_storage_strategy_after_days),
            getString(R.string.settings_storage_strategy_after_count)
        )
        val values = arrayOf("never", "immediate", "after_days", "after_count")
        val current = prefs.getLocalDeleteStrategy()
        val checked = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_storage_strategy)
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                prefs.setLocalDeleteStrategy(values[which])
                updateDeleteStrategyValue()
                updateStorageConditionalVisibility()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateDeleteStrategyValue() {
        val value = when (prefs.getLocalDeleteStrategy()) {
            "immediate" -> getString(R.string.settings_storage_strategy_immediate)
            "after_days" -> getString(R.string.settings_storage_strategy_after_days)
            "after_count" -> getString(R.string.settings_storage_strategy_after_count)
            else -> getString(R.string.settings_storage_strategy_never)
        }
        findViewById<TextView>(R.id.delete_strategy_value).text = value
    }

    private fun updateStorageConditionalVisibility() {
        val strategy = prefs.getLocalDeleteStrategy()
        findViewById<View>(R.id.row_delete_days).visibility =
            if (strategy == "after_days") View.VISIBLE else View.GONE
        findViewById<View>(R.id.row_delete_count).visibility =
            if (strategy == "after_count") View.VISIBLE else View.GONE

        findViewById<EditText>(R.id.edit_delete_days).setText(prefs.getLocalDeleteDays().toString())
        findViewById<EditText>(R.id.edit_delete_count).setText(prefs.getLocalDeleteCount().toString())
    }

    private fun bindStorageCostSection() {
        findViewById<LinearLayout>(R.id.row_cost_dashboard).setOnClickListener {
            startActivity(Intent(this, CostDashboardActivity::class.java))
        }
    }

    private fun bindIndexSection() {
        findViewById<Button>(R.id.btn_backup_index).setOnClickListener {
            IndexSyncScheduler.runNow(this)
            Toast.makeText(this, R.string.index_backup_started, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_restore_index).setOnClickListener {
            startActivity(
                Intent(this, IndexRecoveryActivity::class.java)
                    .putExtra(IndexRecoveryActivity.EXTRA_FORCE_RESTORE_PROMPT, true)
            )
        }
    }

    private fun updateLastIndexSync() {
        val ts = prefs.getLastIndexSyncAt()
        findViewById<TextView>(R.id.last_index_sync).text = if (ts == null) {
            getString(R.string.settings_index_never)
        } else {
            getString(R.string.settings_index_last, DateUtils.getRelativeTimeSpanString(ts))
        }
    }

    private fun bindSharingSection() {
        findViewById<LinearLayout>(R.id.row_share_links).setOnClickListener {
            startActivity(Intent(this, ActiveShareLinksActivity::class.java))
        }
    }

    private fun updateShareLinksCount() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                app.shareLinkService.activeLinks().size
            }
            findViewById<TextView>(R.id.share_links_count).text = if (count == 0) {
                getString(R.string.settings_sharing_none)
            } else {
                getString(R.string.settings_sharing_active_count, count)
            }
        }
    }

    private fun bindAccountSection() {
        findViewById<Button>(R.id.btn_re_enter).setOnClickListener {
            startActivity(
                Intent(this, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_FORCE_RECREDENTIAL, true)
            )
        }

        findViewById<Button>(R.id.btn_sign_out).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.settings_account_sign_out_confirm)
                .setPositiveButton(R.string.settings_account_sign_out) { _, _ ->
                    prefs.clearCredentials()
                    UploadForegroundService.stop(this)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun bindDiagnosticsSection() {
        val switch = findViewById<Switch>(R.id.switch_diagnostics)
        switch.setOnCheckedChangeListener { _, isChecked ->
            val logger = FileLogger.getInstance(this)
            if (isChecked) {
                logger.start()
            } else {
                logger.stop()
            }
            updateDiagnosticsStatus()
        }

        findViewById<Button>(R.id.btn_upload_logs).setOnClickListener {
            uploadLogs()
        }
        findViewById<Button>(R.id.btn_clear_logs).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.settings_diagnostics_clear_confirm)
                .setPositiveButton(R.string.settings_diagnostics_clear) { _, _ ->
                    clearLogs()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun updateDiagnosticsStatus() {
        val logger = FileLogger.getInstance(this)
        val isRunning = logger.isRunning()
        val startedAt = prefs.getDiagnosticsStartedAt()

        findViewById<Switch>(R.id.switch_diagnostics).isChecked = isRunning

        val statusText = when {
            isRunning && startedAt != null -> {
                val elapsed = System.currentTimeMillis() - startedAt
                val remainingMin = ((DIAGNOSTICS_TIMEOUT_MS - elapsed).coerceAtLeast(0) / 60_000).toInt()
                getString(R.string.settings_diagnostics_status_running, remainingMin.coerceAtLeast(1))
            }
            !isRunning && startedAt != null -> getString(R.string.settings_diagnostics_status_auto_stopped)
            else -> getString(R.string.settings_diagnostics_status_stopped)
        }
        findViewById<TextView>(R.id.diagnostics_status).text = statusText
    }

    private fun uploadLogs() {
        val uploader = app.s3Uploader
        if (uploader == null) {
            Toast.makeText(this, R.string.settings_diagnostics_upload_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val logger = FileLogger.getInstance(this)
        val logFile = logger.getLogFile()
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, R.string.settings_diagnostics_empty, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, R.string.settings_diagnostics_uploading, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = logFile.readBytes()
                    uploader.upload(
                        key = LOGS_B2_PATH,
                        contentType = "text/plain",
                        contentLength = bytes.size.toLong(),
                        body = ByteStream.fromBytes(bytes)
                    ).getOrThrow()
                }
            }
            result.fold(
                onSuccess = {
                    Toast.makeText(this@SettingsActivity, R.string.settings_diagnostics_uploaded, Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    Toast.makeText(this@SettingsActivity, R.string.settings_diagnostics_upload_failed, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun clearLogs() {
        val logger = FileLogger.getInstance(this)
        logger.clear()
        logger.stop()

        val uploader = app.s3Uploader
        if (uploader != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    uploader.deleteObject(LOGS_B2_PATH)
                }
                updateDiagnosticsStatus()
                Toast.makeText(this@SettingsActivity, R.string.settings_diagnostics_cleared, Toast.LENGTH_SHORT).show()
            }
        } else {
            updateDiagnosticsStatus()
            Toast.makeText(this, R.string.settings_diagnostics_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindAboutSection() {
        findViewById<TextView>(R.id.about_repo).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BYOYPhotoStorage/photoStorage"))
            )
        }
    }

    private fun updateAboutSection() {
        val info = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
        val versionName = info.versionName.orEmpty()
        val versionCode = info.longVersionCode
        findViewById<TextView>(R.id.about_version).text = getString(
            R.string.settings_about_version,
            versionName,
            versionCode
        )
        val endpoint = prefs.getCredentials()?.let { creds ->
            "s3.${creds.bucketName}.backblazeb2.com"
        } ?: "Not configured"
        findViewById<TextView>(R.id.about_endpoint).text =
            getString(R.string.settings_about_endpoint, endpoint)
    }

    companion object {
        private const val LOGS_B2_PATH = "logs/app.log"
        private val DIAGNOSTICS_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15)
    }
}
