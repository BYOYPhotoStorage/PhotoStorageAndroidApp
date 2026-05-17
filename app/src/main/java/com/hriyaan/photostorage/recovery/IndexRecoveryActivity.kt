package com.hriyaan.photostorage.recovery

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.databinding.ActivityIndexRecoveryBinding
import com.hriyaan.photostorage.ui.GalleryActivity
import com.hriyaan.photostorage.worker.NightlyScanScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class IndexRecoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIndexRecoveryBinding

    private val app: PhotoBackupApp get() = application as PhotoBackupApp
    private val prefsStore by lazy { app.prefsStore }
    private val galleryRepository by lazy { app.galleryRepository }

    private var s3Uploader: S3Uploader? = null
    private var recoveryService: IndexRecoveryService? = null
    private var forceRestorePrompt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIndexRecoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        forceRestorePrompt = intent.getBooleanExtra(EXTRA_FORCE_RESTORE_PROMPT, false)

        val creds = prefsStore.getCredentials()
        if (creds == null) {
            finish()
            return
        }

        val uploader = S3Uploader(
            S3ClientFactory.create(creds, S3Config.forBucket(creds.bucketName)),
            creds.bucketName
        )
        s3Uploader = uploader
        recoveryService = IndexRecoveryService(
            applicationContext,
            uploader,
            app.uploadDatabase.dao,
            app.uploadDatabase,
            prefsStore
        )

        renderChecking()
        lifecycleScope.launch { checkForRemoteIndex() }
    }

    override fun onDestroy() {
        super.onDestroy()
        s3Uploader?.close()
    }

    private suspend fun checkForRemoteIndex() {
        val service = recoveryService ?: return
        val result = withContext(Dispatchers.IO) { service.hasRemoteIndex() }
        result.fold(
            onSuccess = { info ->
                if (info == null) {
                    showCatchUpPrompt(latestUploadedAt = null)
                } else {
                    showFound(info)
                }
            },
            onFailure = {
                showCatchUpPrompt(latestUploadedAt = null)
            }
        )
    }

    private fun renderChecking() {
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.title.text = getString(R.string.recovery_checking)
        binding.body.text = ""
        binding.buttons.removeAllViews()
    }

    private fun showFound(info: RemoteIndexInfo) {
        binding.progress.visibility = View.GONE
        binding.title.text = getString(R.string.recovery_found_title)
        binding.body.text = if (info.lastModified != null) {
            val formatted = DateFormat.getMediumDateFormat(this).format(Date(info.lastModified))
            getString(R.string.recovery_found_body, formatted)
        } else {
            getString(R.string.recovery_found_body_unknown)
        }
        binding.buttons.removeAllViews()
        addButton(R.string.recovery_restore_button) { onRestoreClicked() }
        addButton(R.string.recovery_start_fresh_button) {
            showCatchUpPrompt(latestUploadedAt = null)
        }
    }

    private fun onRestoreClicked() {
        if (forceRestorePrompt) {
            AlertDialog.Builder(this)
                .setMessage(R.string.recovery_replace_confirm)
                .setPositiveButton(R.string.recovery_restore_button) { _, _ -> startRestore() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            startRestore()
        }
    }

    private fun startRestore() {
        val service = recoveryService ?: return
        renderRestoring()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { service.downloadAndRestore() }
            result.fold(
                onSuccess = { outcome -> showRestored(outcome) },
                onFailure = { showFailed(it) }
            )
        }
    }

    private fun renderRestoring() {
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.title.text = getString(R.string.recovery_restoring)
        binding.body.text = ""
        binding.buttons.removeAllViews()
    }

    private fun showRestored(outcome: RestoreOutcome) {
        binding.progress.visibility = View.GONE
        binding.title.text = getString(R.string.recovery_restored_title)
        binding.body.text = getString(R.string.recovery_restored_body, outcome.photoCount)
        binding.buttons.removeAllViews()
        galleryRepository.invalidate()
        lifecycleScope.launch {
            delay(1500)
            showCatchUpPrompt(latestUploadedAt = outcome.latestUploadedAt)
        }
    }

    private fun showFailed(error: Throwable) {
        binding.progress.visibility = View.GONE
        binding.title.text = getString(R.string.recovery_failed_title)
        val message = when (error) {
            is IndexRecoveryService.IndexTooNewException ->
                getString(R.string.recovery_index_too_new)
            else -> error.message ?: getString(R.string.recovery_index_invalid)
        }
        binding.body.text = getString(R.string.recovery_failed_body, message)
        binding.buttons.removeAllViews()
        addButton(R.string.recovery_start_fresh_button) {
            showCatchUpPrompt(latestUploadedAt = null)
        }
    }

    private fun showCatchUpPrompt(latestUploadedAt: Long?) {
        binding.progress.visibility = View.GONE
        binding.title.text = getString(R.string.catchup_title)
        binding.body.text = if (latestUploadedAt != null) {
            val formatted = DateFormat.getMediumDateFormat(this).format(Date(latestUploadedAt))
            getString(R.string.catchup_body_with_date, formatted)
        } else {
            getString(R.string.catchup_body_no_date)
        }
        binding.buttons.removeAllViews()
        if (latestUploadedAt != null) {
            addButton(R.string.catchup_from_date) {
                dispatchCatchUp(CatchUpOption.CATCH_UP, latestUploadedAt)
            }
        }
        addButton(R.string.catchup_full) {
            dispatchCatchUp(CatchUpOption.FULL_RESCAN, latestUploadedAt)
        }
        addButton(R.string.catchup_skip) {
            dispatchCatchUp(CatchUpOption.SKIP, latestUploadedAt)
        }
    }

    private fun dispatchCatchUp(option: CatchUpOption, latestUploadedAt: Long?) {
        binding.buttons.removeAllViews()
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                when (option) {
                    CatchUpOption.CATCH_UP -> NightlyScanScheduler.runOnce(
                        applicationContext,
                        latestUploadedAt
                    )
                    CatchUpOption.FULL_RESCAN -> NightlyScanScheduler.runOnce(
                        applicationContext,
                        0L
                    )
                    CatchUpOption.SKIP -> Unit
                }
                recoveryService?.reconcileLocalPresent()
            }
            galleryRepository.invalidate()
            prefsStore.setRecoveryFlowCompleted(true)
            goToGallery()
        }
    }

    private fun goToGallery() {
        startActivity(
            Intent(this, GalleryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun addButton(textRes: Int, onClick: () -> Unit) {
        val button = Button(this)
        button.text = getString(textRes)
        button.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (8 * resources.displayMetrics.density).toInt()
        }
        binding.buttons.addView(button, params)
    }

    private enum class CatchUpOption { CATCH_UP, FULL_RESCAN, SKIP }

    companion object {
        const val EXTRA_FORCE_RESTORE_PROMPT = "force_restore_prompt"
    }
}
