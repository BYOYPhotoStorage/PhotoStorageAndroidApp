package com.hriyaan.photostorage.ui

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import aws.smithy.kotlin.runtime.content.ByteStream
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.MediaStoreQuery
import com.hriyaan.photostorage.data.PhotoPermission
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.databinding.ActivityGalleryBinding
import com.hriyaan.photostorage.service.UploadForegroundService
import com.hriyaan.photostorage.thumbnail.ThumbnailGen
import com.hriyaan.photostorage.worker.NightlyScanScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter

    private val mediaStoreQuery by lazy { MediaStoreQuery(applicationContext) }
    private val uploadDao by lazy { (application as PhotoBackupApp).uploadDatabase.dao }
    private val prefsStore by lazy { (application as PhotoBackupApp).prefsStore }
    private val thumbnailGen by lazy { ThumbnailGen(applicationContext) }

    private var s3Uploader: S3Uploader? = null
    private val inFlight = mutableSetOf<String>()
    private var statusRefreshJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadGallery()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort; if denied, the OS silently suppresses notifications */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.title_gallery)

        adapter = GalleryAdapter(::onTap, ::onLongPress)
        binding.galleryRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.galleryRecyclerView.adapter = adapter

        val creds = prefsStore.getCredentials()
        if (creds == null) {
            finish()
            return
        }
        s3Uploader = S3Uploader(
            S3ClientFactory.create(creds, S3Config.forBucket(creds.bucketName)),
            creds.bucketName
        )

        binding.autoUploadSwitch.isChecked = prefsStore.isAutoUploadEnabled()
        binding.wifiOnlySwitch.isChecked = prefsStore.isWifiOnly()
        binding.autoUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefsStore.setAutoUploadEnabled(isChecked)
            if (isChecked) {
                UploadForegroundService.start(this)
                NightlyScanScheduler.schedule(this)
            } else {
                UploadForegroundService.stop(this)
                NightlyScanScheduler.cancel(this)
            }
            updateStatusText()
        }
        binding.wifiOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefsStore.setWifiOnly(isChecked)
            updateStatusText()
        }
        if (binding.autoUploadSwitch.isChecked) {
            UploadForegroundService.start(this)
        }
        updateStatusText()

        maybeRequestNotificationPermission()

        if (PhotoPermission.isGranted(this)) {
            loadGallery()
        } else {
            permissionLauncher.launch(PhotoPermission.PERMISSION)
        }
    }

    override fun onResume() {
        super.onResume()
        statusRefreshJob = lifecycleScope.launch {
            while (isActive) {
                updateStatusText()
                delay(STATUS_REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        statusRefreshJob?.cancel()
        statusRefreshJob = null
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadGallery() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val photos = mediaStoreQuery.queryAllPhotos()
                photos.map { p ->
                    GalleryItem(p, uploadDao.findByFilenameAndSize(p.filename, p.size))
                }
            }
            adapter.submit(items)
        }
    }

    private fun refreshItem(photoId: Long, filename: String, size: Long) {
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                uploadDao.findByFilenameAndSize(filename, size)
            }
            val current = adapter.findByPhotoId(photoId) ?: return@launch
            adapter.updateItem(photoId, current.copy(record = updated))
        }
    }

    private fun updateStatusText() {
        lifecycleScope.launch {
            val (pending, retrying, permanentlyFailed) = withContext(Dispatchers.IO) {
                Triple(
                    uploadDao.getPendingQueue().size,
                    uploadDao.getFailedRetryable().size,
                    uploadDao.getAll().count { it.status == UploadDao.STATUS_PERMANENTLY_FAILED }
                )
            }
            val parts = mutableListOf<String>()
            if (pending > 0) parts += getString(R.string.status_pending, pending)
            if (retrying > 0) parts += getString(R.string.status_retrying, retrying)
            if (permanentlyFailed > 0) parts += getString(R.string.status_failed, permanentlyFailed)
            if (parts.isEmpty()) parts += getString(R.string.status_caught_up)
            if (prefsStore.isAutoUploadEnabled() && prefsStore.isWifiOnly()) {
                parts += getString(R.string.status_wifi_only)
            }
            if (!prefsStore.isAutoUploadEnabled()) {
                parts += getString(R.string.status_manual)
            }
            binding.statusText.text = parts.joinToString(" ")
        }
    }

    private fun onTap(item: GalleryItem) {
        if (item.record?.status == UploadDao.STATUS_UPLOADED) {
            Toast.makeText(this, R.string.already_uploaded, Toast.LENGTH_SHORT).show()
            return
        }
        val uriKey = item.photo.uri.toString()
        if (!inFlight.add(uriKey)) return

        val uploader = s3Uploader ?: run {
            inFlight.remove(uriKey)
            return
        }

        lifecycleScope.launch {
            try {
                val rowId = withContext(Dispatchers.IO) {
                    val existing = uploadDao.findByFilenameAndSize(
                        item.photo.filename,
                        item.photo.size
                    )
                    if (existing != null) {
                        uploadDao.updateStatus(existing.id, UploadDao.STATUS_UPLOADING)
                        existing.id
                    } else {
                        uploadDao.insert(
                            UploadRecord(
                                localUri = item.photo.uri.toString(),
                                filename = item.photo.filename,
                                size = item.photo.size,
                                dateTaken = item.photo.dateTakenMs,
                                photoB2Path = null,
                                thumbnailB2Path = null,
                                status = UploadDao.STATUS_UPLOADING,
                                uploadedAt = null
                            )
                        )
                    }
                }
                refreshItem(item.photo.id, item.photo.filename, item.photo.size)

                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        val photoKey = S3KeyBuilder.photoKey(
                            item.photo.filename,
                            item.photo.dateTakenMs
                        )
                        val thumbKey = S3KeyBuilder.thumbnailKey(
                            item.photo.filename,
                            item.photo.dateTakenMs
                        )

                        val bytes = contentResolver.openInputStream(item.photo.uri)?.use {
                            it.readBytes()
                        } ?: throw IOException("Could not open ${item.photo.uri}")

                        uploader.upload(
                            key = photoKey,
                            contentType = "image/jpeg",
                            contentLength = bytes.size.toLong(),
                            body = ByteStream.fromBytes(bytes)
                        ).getOrThrow()

                        val thumbBytes = thumbnailGen.createWebPThumbnail(item.photo.uri)
                        uploader.upload(
                            key = thumbKey,
                            contentType = "image/webp",
                            contentLength = thumbBytes.size.toLong(),
                            body = ByteStream.fromBytes(thumbBytes)
                        ).getOrThrow()

                        uploadDao.setUploadedPaths(
                            id = rowId,
                            photoPath = photoKey,
                            thumbnailPath = thumbKey,
                            uploadedAt = System.currentTimeMillis()
                        )
                    }
                }
                outcome.onFailure {
                    withContext(Dispatchers.IO) {
                        uploadDao.updateStatus(rowId, UploadDao.STATUS_FAILED)
                    }
                    Toast.makeText(
                        this@GalleryActivity,
                        R.string.upload_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshItem(item.photo.id, item.photo.filename, item.photo.size)
            } finally {
                inFlight.remove(uriKey)
            }
        }
    }

    private fun onLongPress(item: GalleryItem) {
        val record = item.record
        if (record?.status == UploadDao.STATUS_PERMANENTLY_FAILED) {
            showRetryDialog(item, record)
        } else {
            showInfoDialog(item)
        }
    }

    private fun showInfoDialog(item: GalleryItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.info_title)
            .setMessage(buildInfoMessage(item))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showRetryDialog(item: GalleryItem, record: UploadRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.info_title)
            .setMessage(buildInfoMessage(item))
            .setPositiveButton(R.string.retry) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        uploadDao.updateRetry(record.id, 0, null)
                        uploadDao.updateStatus(record.id, UploadDao.STATUS_PENDING)
                    }
                    UploadForegroundService.start(this@GalleryActivity)
                    refreshItem(item.photo.id, item.photo.filename, item.photo.size)
                    updateStatusText()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildInfoMessage(item: GalleryItem): String {
        val df = DateFormat.getDateTimeInstance()
        val dateStr = if (item.photo.dateTakenMs > 0) df.format(Date(item.photo.dateTakenMs)) else "—"
        val sizeStr = formatSize(item.photo.size)
        return getString(R.string.info_filename, item.photo.filename) + "\n" +
                getString(R.string.info_size, sizeStr) + "\n" +
                getString(R.string.info_date, dateStr)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        s3Uploader?.close()
    }

    companion object {
        private const val STATUS_REFRESH_INTERVAL_MS = 5000L
    }
}
