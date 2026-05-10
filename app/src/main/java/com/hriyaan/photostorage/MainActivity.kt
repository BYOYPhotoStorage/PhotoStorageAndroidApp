package com.hriyaan.photostorage

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hriyaan.photostorage.ui.GalleryActivity
import com.hriyaan.photostorage.ui.OnboardingActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PhotoBackupApp
        val target = if (app.prefsStore.hasCredentials()) {
            GalleryActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
