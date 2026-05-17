package com.hriyaan.photostorage

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hriyaan.photostorage.recovery.IndexRecoveryActivity
import com.hriyaan.photostorage.ui.GalleryActivity
import com.hriyaan.photostorage.ui.OnboardingActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PhotoBackupApp

        if (!app.prefsStore.hasCredentials()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (app.prefsStore.getLastIndexSyncAt() == null &&
            !app.prefsStore.hasCompletedRecoveryFlow()
        ) {
            startActivity(Intent(this, IndexRecoveryActivity::class.java))
            finish()
            return
        }

        startActivity(Intent(this, GalleryActivity::class.java))
        finish()
    }
}
