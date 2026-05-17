package com.hriyaan.photostorage.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.service.UploadForegroundService
import com.hriyaan.photostorage.worker.InitialBackfillWorker
import kotlinx.coroutines.launch

class FirstBackupActivity : AppCompatActivity() {

    private enum class Step { ENABLE, SCOPE, VIDEOS, DONE }

    private var step: Step = Step.ENABLE
    private var autoUploadChoice: Boolean? = null
    private var scopeChoice: String = "today"
    private var videosChoice: Boolean = false

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var contentSlot: FrameLayout
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button

    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            videosChoice = false
            Toast.makeText(this, R.string.first_backup_videos_permission_denied, Toast.LENGTH_SHORT).show()
        }
        advanceTo(Step.DONE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_first_backup)

        titleView = findViewById(R.id.title)
        bodyView = findViewById(R.id.body)
        contentSlot = findViewById(R.id.content_slot)
        btnPrimary = findViewById(R.id.btn_primary)
        btnSecondary = findViewById(R.id.btn_secondary)

        renderStep(step)
    }

    override fun onBackPressed() {
        when (step) {
            Step.ENABLE -> super.onBackPressed()
            Step.SCOPE -> {
                step = Step.ENABLE
                renderStep(Step.ENABLE)
            }
            Step.VIDEOS -> {
                step = Step.SCOPE
                renderStep(Step.SCOPE)
            }
            Step.DONE -> {
                step = Step.VIDEOS
                renderStep(Step.VIDEOS)
            }
        }
    }

    private fun renderStep(s: Step) {
        contentSlot.removeAllViews()
        when (s) {
            Step.ENABLE -> renderEnable()
            Step.SCOPE -> renderScope()
            Step.VIDEOS -> renderVideos()
            Step.DONE -> commitAndFinish()
        }
    }

    private fun advanceTo(next: Step) {
        step = next
        renderStep(next)
    }

    private fun renderEnable() {
        titleView.text = getString(R.string.first_backup_enable_title)
        bodyView.text = getString(R.string.first_backup_enable_body)

        btnSecondary.visibility = Button.VISIBLE
        btnSecondary.text = getString(R.string.first_backup_enable_secondary)
        btnSecondary.setOnClickListener {
            autoUploadChoice = false
            advanceTo(Step.DONE)
        }

        btnPrimary.text = getString(R.string.first_backup_enable_primary)
        btnPrimary.setOnClickListener {
            autoUploadChoice = true
            advanceTo(Step.SCOPE)
        }
    }

    private fun renderScope() {
        titleView.text = getString(R.string.first_backup_scope_title)
        bodyView.text = getString(R.string.first_backup_scope_body)

        btnSecondary.visibility = Button.GONE

        val radioGroup = RadioGroup(this).apply {
            val todayBtn = RadioButton(this@FirstBackupActivity).apply {
                text = getString(R.string.first_backup_scope_today)
                id = R.id.radio_today
                isChecked = true
            }
            val allBtn = RadioButton(this@FirstBackupActivity).apply {
                text = getString(R.string.first_backup_scope_all)
                id = R.id.radio_all
            }
            addView(todayBtn)
            addView(allBtn)
        }
        contentSlot.addView(radioGroup)

        btnPrimary.text = getString(R.string.first_backup_scope_continue)
        btnPrimary.setOnClickListener {
            scopeChoice = when (radioGroup.checkedRadioButtonId) {
                R.id.radio_all -> "all"
                else -> "today"
            }
            advanceTo(Step.VIDEOS)
        }
    }

    private fun renderVideos() {
        titleView.text = getString(R.string.first_backup_videos_title)
        bodyView.text = getString(R.string.first_backup_videos_body)

        btnSecondary.visibility = Button.GONE

        val switch = Switch(this).apply {
            text = getString(R.string.first_backup_videos_toggle)
            isChecked = false
        }
        contentSlot.addView(switch)

        btnPrimary.text = getString(R.string.first_backup_videos_finish)
        btnPrimary.setOnClickListener {
            videosChoice = switch.isChecked
            if (videosChoice) {
                videoPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                advanceTo(Step.DONE)
            }
        }
    }

    private fun commitAndFinish() {
        val app = application as PhotoBackupApp
        val prefs = app.prefsStore

        val enabled = autoUploadChoice == true

        prefs.setAutoUploadEnabled(enabled)
        prefs.setFirstBackupScope(scopeChoice)
        prefs.setVideosEnabled(videosChoice)
        prefs.setFirstBackupFlowCompleted(true)

        if (enabled && scopeChoice == "all") {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "initial_backfill",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<InitialBackfillWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(
                                if (prefs.isWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
                            )
                            .build()
                    )
                    .build()
            )
        }

        if (enabled) {
            UploadForegroundService.start(this)
        }

        finish()
    }
}
