package com.hriyaan.photostorage.ui

import android.Manifest
import android.app.DatePickerDialog
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
import androidx.work.workDataOf
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.service.UploadForegroundService
import com.hriyaan.photostorage.worker.InitialBackfillWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class FirstBackupActivity : AppCompatActivity() {

    private enum class Step { ENABLE, SCOPE, VIDEOS, DONE }
    private enum class ScopeOption { NEW_ONLY, WEEKS_2, WEEKS_4, ALL, CUSTOM }

    private var step: Step = Step.ENABLE
    private var autoUploadChoice: Boolean? = null
    private var scopeChoice: ScopeOption = ScopeOption.NEW_ONLY
    private var customDateMs: Long? = null
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
            val newOnlyBtn = RadioButton(this@FirstBackupActivity).apply {
                text = getString(R.string.first_backup_scope_new_only)
                id = R.id.radio_new_only
                isChecked = scopeChoice == ScopeOption.NEW_ONLY
            }
            val weeks2Btn = RadioButton(this@FirstBackupActivity).apply {
                text = getString(R.string.first_backup_scope_2_weeks)
                id = R.id.radio_2_weeks
                isChecked = scopeChoice == ScopeOption.WEEKS_2
            }
            val weeks4Btn = RadioButton(this@FirstBackupActivity).apply {
                text = getString(R.string.first_backup_scope_4_weeks)
                id = R.id.radio_4_weeks
                isChecked = scopeChoice == ScopeOption.WEEKS_4
            }
            val allBtn = RadioButton(this@FirstBackupActivity).apply {
                text = getString(R.string.first_backup_scope_all)
                id = R.id.radio_all
                isChecked = scopeChoice == ScopeOption.ALL
            }
            val customBtn = RadioButton(this@FirstBackupActivity).apply {
                text = customDateLabel()
                id = R.id.radio_custom_date
                isChecked = scopeChoice == ScopeOption.CUSTOM
            }
            addView(newOnlyBtn)
            addView(weeks2Btn)
            addView(weeks4Btn)
            addView(allBtn)
            addView(customBtn)

            setOnCheckedChangeListener { _, checkedId ->
                scopeChoice = when (checkedId) {
                    R.id.radio_2_weeks -> ScopeOption.WEEKS_2
                    R.id.radio_4_weeks -> ScopeOption.WEEKS_4
                    R.id.radio_all -> ScopeOption.ALL
                    R.id.radio_custom_date -> ScopeOption.CUSTOM
                    else -> ScopeOption.NEW_ONLY
                }
            }
        }
        contentSlot.addView(radioGroup)

        btnPrimary.text = getString(R.string.first_backup_scope_continue)
        btnPrimary.setOnClickListener {
            if (scopeChoice == ScopeOption.CUSTOM && customDateMs == null) {
                showDatePicker(radioGroup)
            } else {
                advanceTo(Step.VIDEOS)
            }
        }
    }

    private fun customDateLabel(): String {
        val date = customDateMs
        return if (date != null) {
            val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            getString(R.string.first_backup_scope_custom_format, fmt.format(date))
        } else {
            getString(R.string.first_backup_scope_custom)
        }
    }

    private fun showDatePicker(radioGroup: RadioGroup) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(year, month, day, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                customDateMs = cal.timeInMillis
                val customBtn = radioGroup.findViewById<RadioButton>(R.id.radio_custom_date)
                customBtn?.text = customDateLabel()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
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
        val since = when (scopeChoice) {
            ScopeOption.NEW_ONLY -> System.currentTimeMillis()
            ScopeOption.WEEKS_2 -> System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
            ScopeOption.WEEKS_4 -> System.currentTimeMillis() - 28L * 24 * 60 * 60 * 1000
            ScopeOption.ALL -> 0L
            ScopeOption.CUSTOM -> customDateMs ?: System.currentTimeMillis()
        }

        prefs.setAutoUploadEnabled(enabled)
        prefs.setFirstBackupSince(since)
        prefs.setVideosEnabled(videosChoice)
        prefs.setFirstBackupFlowCompleted(true)

        if (enabled && since == 0L) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "initial_backfill",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<InitialBackfillWorker>()
                    .setInputData(workDataOf(InitialBackfillWorker.KEY_SINCE to 0L))
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

        if (enabled && since > 0L) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "initial_backfill",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<InitialBackfillWorker>()
                    .setInputData(workDataOf(InitialBackfillWorker.KEY_SINCE to since))
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
