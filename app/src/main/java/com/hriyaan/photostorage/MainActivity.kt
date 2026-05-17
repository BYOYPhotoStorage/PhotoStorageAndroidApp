package com.hriyaan.photostorage

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hriyaan.photostorage.recovery.IndexRecoveryActivity
import com.hriyaan.photostorage.ui.FirstBackupActivity
import com.hriyaan.photostorage.ui.GalleryActivity
import com.hriyaan.photostorage.ui.OnboardingActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PhotoBackupApp
        val prefs = app.prefsStore

        when {
            !prefs.hasCredentials() -> {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
            prefs.getLastIndexSyncAt() == null && !prefs.hasCompletedRecoveryFlow() -> {
                startActivity(Intent(this, IndexRecoveryActivity::class.java))
            }
            !prefs.hasCompletedFirstBackupFlow() -> {
                startActivity(Intent(this, FirstBackupActivity::class.java))
            }
            else -> {
                startActivity(Intent(this, GalleryActivity::class.java))
            }
        }
        finish()
    }
}
