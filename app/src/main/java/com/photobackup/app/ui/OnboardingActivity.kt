package com.photobackup.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import aws.sdk.kotlin.services.s3.model.NoSuchBucket
import aws.sdk.kotlin.services.s3.model.S3Exception
import com.photobackup.app.PhotoBackupApp
import com.photobackup.app.R
import com.photobackup.app.b2.S3ClientFactory
import com.photobackup.app.b2.S3Config
import com.photobackup.app.b2.S3Uploader
import com.photobackup.app.data.B2Credentials
import com.photobackup.app.data.PrefsStore
import com.photobackup.app.databinding.ActivityOnboardingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val prefsStore: PrefsStore by lazy {
        (application as PhotoBackupApp).prefsStore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.connectButton.setOnClickListener { onConnect() }
    }

    private fun onConnect() {
        val keyId = binding.keyIdInput.text?.toString()?.trim().orEmpty()
        val appKey = binding.appKeyInput.text?.toString()?.trim().orEmpty()
        val bucket = binding.bucketInput.text?.toString()?.trim().orEmpty()

        if (keyId.isEmpty() || appKey.isEmpty() || bucket.isEmpty()) {
            showError(getString(R.string.error_blank_fields))
            return
        }

        binding.connectButton.isEnabled = false
        binding.progressBar.isVisible = true
        binding.errorText.isVisible = false

        val creds = B2Credentials(keyId, appKey, bucket)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val client = S3ClientFactory.create(creds, S3Config.forBucket(bucket))
                val uploader = S3Uploader(client, bucket)
                try {
                    uploader.validateCredentials()
                } finally {
                    uploader.close()
                }
            }
            result.onSuccess {
                prefsStore.saveCredentials(creds)
                startActivity(Intent(this@OnboardingActivity, GalleryActivity::class.java))
                finish()
            }.onFailure { e ->
                showError(messageFor(e))
                binding.connectButton.isEnabled = true
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun showError(text: String) {
        binding.errorText.text = text
        binding.errorText.isVisible = true
    }

    private fun messageFor(e: Throwable): String {
        if (e is NoSuchBucket) return getString(R.string.error_bucket_not_found)
        if (e is S3Exception) {
            when (e.sdkErrorMetadata.errorCode) {
                "InvalidAccessKeyId" -> return getString(R.string.error_invalid_key_id)
                "SignatureDoesNotMatch" -> return getString(R.string.error_invalid_app_key)
                "NoSuchBucket" -> return getString(R.string.error_bucket_not_found)
            }
            return getString(R.string.error_generic_format, e.javaClass.simpleName)
        }
        if (e is IOException) return getString(R.string.error_network)
        return getString(R.string.error_generic_format, e.javaClass.simpleName)
    }
}
