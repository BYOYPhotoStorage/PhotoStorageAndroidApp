package com.hriyaan.photostorage.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import aws.smithy.kotlin.runtime.content.ByteStream
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.PhotoPermission
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.databinding.ActivityGalleryBinding
import com.hriyaan.photostorage.gallery.DeletionEngine
import com.hriyaan.photostorage.gallery.GalleryItem
import com.hriyaan.photostorage.gallery.GalleryViewMode
import com.hriyaan.photostorage.gallery.MediaStoreDeleteLauncher
import com.hriyaan.photostorage.recovery.IndexRecoveryActivity
import com.hriyaan.photostorage.service.UploadForegroundService
import com.hriyaan.photostorage.thumbnail.ThumbnailCacheFactory
import com.hriyaan.photostorage.thumbnail.ThumbnailGen
import com.hriyaan.photostorage.worker.IndexSyncScheduler
import com.hriyaan.photostorage.worker.NightlyScanScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter

    private val uploadDao by lazy { app.uploadDatabase.dao }
    private val prefsStore by lazy { app.prefsStore }
    private val galleryRepository by lazy { app.galleryRepository }
    private val thumbnailGen by lazy { ThumbnailGen(applicationContext) }
    private val app: PhotoBackupApp get() = application as PhotoBackupApp

    private var s3Uploader: S3Uploader? = null
    private var deletionEngine: DeletionEngine? = null
    private var thumbnailCacheFactory: ThumbnailCacheFactory? = null

    private var currentMode: GalleryViewMode = GalleryViewMode.MERGED
    private var galleryJob: Job? = null
    private var statusRefreshJob: Job? = null

    private val inFlight = mutableSetOf<String>()
    private val selection = linkedSetOf<String>()
    private var actionMode: ActionMode? = null

    private var pendingDeleteDeferred: CompletableDeferred<Boolean>? = null

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingDeleteDeferred?.complete(result.resultCode == Activity.RESULT_OK)
        pendingDeleteDeferred = null
    }

    private val mediaStoreDeleteLauncher = MediaStoreDeleteLauncher { uris ->
        if (uris.isEmpty()) return@MediaStoreDeleteLauncher true
        val pi = MediaStore.createDeleteRequest(contentResolver, uris)
        val deferred = CompletableDeferred<Boolean>()
        pendingDeleteDeferred = deferred
        deleteRequestLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        deferred.await()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startObservingMode(currentMode)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menuInflater.inflate(R.menu.menu_gallery_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.title = getString(R.string.selection_title, selection.size)
            val shareItem = menu.findItem(R.id.action_share_link)
            if (shareItem != null) {
                val canShare = selection.size == 1 &&
                    adapter.findById(selection.first()) !is GalleryItem.LocalOnly
                shareItem.isEnabled = canShare
            }
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_share_link -> {
                    onShareLinkClicked()
                    true
                }
                R.id.action_delete -> {
                    onDeleteSelected()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            selection.clear()
            adapter.setSelection(emptySet())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.title_gallery)

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

        val cacheFactory = ThumbnailCacheFactory(applicationContext, uploader, prefsStore)
        thumbnailCacheFactory = cacheFactory

        deletionEngine = DeletionEngine(
            applicationContext,
            uploadDao,
            uploader,
            galleryRepository,
            mediaStoreDeleteLauncher
        )

        adapter = GalleryAdapter(cacheFactory.get(), ::onTap, ::onLongPress)
        binding.galleryRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.galleryRecyclerView.adapter = adapter

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

        currentMode = GalleryViewMode.fromKey(prefsStore.getGalleryViewMode())
        when (currentMode) {
            GalleryViewMode.LOCAL -> binding.viewModeGroup.check(R.id.modeLocal)
            GalleryViewMode.CLOUD -> binding.viewModeGroup.check(R.id.modeCloud)
            GalleryViewMode.MERGED -> binding.viewModeGroup.check(R.id.modeMerged)
        }
        binding.viewModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.modeLocal -> GalleryViewMode.LOCAL
                R.id.modeCloud -> GalleryViewMode.CLOUD
                else -> GalleryViewMode.MERGED
            }
            if (mode == currentMode) return@addOnButtonCheckedListener
            prefsStore.setGalleryViewMode(mode.key)
            currentMode = mode
            actionMode?.finish()
            startObservingMode(mode)
        }

        renderIndexSyncStatus()

        maybeRequestNotificationPermission()

        if (PhotoPermission.isGranted(this)) {
            startObservingMode(currentMode)
        } else {
            permissionLauncher.launch(PhotoPermission.PERMISSION)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_gallery, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_backup_index -> {
                IndexSyncScheduler.runNow(this)
                Toast.makeText(this, R.string.index_backup_started, Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    delay(STATUS_REFRESH_INTERVAL_MS)
                    renderIndexSyncStatus()
                }
                true
            }
            R.id.action_restore_index -> {
                startActivity(
                    Intent(this, IndexRecoveryActivity::class.java)
                        .putExtra(IndexRecoveryActivity.EXTRA_FORCE_RESTORE_PROMPT, true)
                )
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        renderIndexSyncStatus()
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

    override fun onDestroy() {
        super.onDestroy()
        s3Uploader?.close()
    }

    private fun startObservingMode(mode: GalleryViewMode) {
        galleryJob?.cancel()
        galleryJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                galleryRepository.observe(mode).collect { items ->
                    adapter.submitList(items)
                }
            }
        }
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

    private fun renderIndexSyncStatus() {
        val ts = prefsStore.getLastIndexSyncAt()
        binding.indexSyncStatus.text = if (ts == null) {
            getString(R.string.index_never_synced)
        } else {
            getString(R.string.index_synced_at, DateUtils.getRelativeTimeSpanString(ts))
        }
    }

    private fun showAboutDialog() {
        val info = packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0L)
        )
        val versionName = info.versionName.orEmpty()
        val versionCode = info.longVersionCode
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_version, versionName, versionCode))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun onTap(item: GalleryItem) {
        if (actionMode != null) {
            toggleSelection(item)
            return
        }
        if (item is GalleryItem.LocalOnly && item.queuedRecord == null) {
            uploadLocalNow(item)
        }
    }

    private fun onLongPress(item: GalleryItem) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(actionModeCallback)
        }
        toggleSelection(item)
    }

    private fun toggleSelection(item: GalleryItem) {
        if (!selection.add(item.id)) selection.remove(item.id)
        adapter.setSelection(selection)
        if (selection.isEmpty()) {
            actionMode?.finish()
        } else {
            actionMode?.invalidate()
        }
    }

    private fun onDeleteSelected() {
        val engine = deletionEngine ?: return
        val items = selection.mapNotNull { id -> adapter.findById(id) }
        if (items.isEmpty()) {
            actionMode?.finish()
            return
        }
        val mode = currentMode
        val title = when (mode) {
            GalleryViewMode.LOCAL -> getString(R.string.delete_local_title)
            GalleryViewMode.CLOUD -> getString(R.string.delete_cloud_title)
            GalleryViewMode.MERGED -> getString(R.string.delete_merged_title)
        }
        val body = when (mode) {
            GalleryViewMode.LOCAL -> getString(R.string.delete_local_body)
            GalleryViewMode.CLOUD -> getString(R.string.delete_cloud_body)
            GalleryViewMode.MERGED -> getString(R.string.delete_merged_body)
        }
        val positive = if (mode == GalleryViewMode.MERGED) {
            getString(R.string.delete_both_button)
        } else {
            getString(R.string.delete_button)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(positive) { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        engine.deleteBatch(items, mode)
                    }
                    val summary = result.summary.ifEmpty {
                        getString(R.string.status_caught_up)
                    }
                    Toast.makeText(this@GalleryActivity, summary, Toast.LENGTH_LONG).show()
                    actionMode?.finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onShareLinkClicked() {
        if (selection.size != 1) return
        val item = adapter.findById(selection.first()) ?: return
        if (item is GalleryItem.LocalOnly) {
            Toast.makeText(this, R.string.share_link_local_only_warning, Toast.LENGTH_SHORT).show()
            return
        }
        ShareLinkDialog.newInstance(item.id).show(supportFragmentManager, "share_link")
        actionMode?.finish()
    }

    private fun uploadLocalNow(item: GalleryItem.LocalOnly) {
        val uploader = s3Uploader ?: return
        val uriKey = item.mediaStoreUri.toString()
        if (!inFlight.add(uriKey)) return

        lifecycleScope.launch {
            try {
                val isVideo = contentResolver.getType(item.mediaStoreUri)
                    ?.startsWith("video/") == true

                val rowId = withContext(Dispatchers.IO) {
                    val existing = uploadDao.findByFilenameAndSize(item.filename, item.sizeBytes)
                    if (existing != null) {
                        uploadDao.updateStatus(existing.id, UploadDao.STATUS_UPLOADING)
                        existing.id
                    } else {
                        uploadDao.insert(
                            UploadRecord(
                                localUri = uriKey,
                                filename = item.filename,
                                size = item.sizeBytes,
                                dateTaken = item.dateTaken,
                                photoB2Path = null,
                                thumbnailB2Path = null,
                                status = UploadDao.STATUS_UPLOADING,
                                uploadedAt = null,
                                mediaType = if (isVideo) UploadDao.MEDIA_TYPE_VIDEO else UploadDao.MEDIA_TYPE_PHOTO
                            )
                        )
                    }
                }
                galleryRepository.invalidate()

                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        val thumbKey = S3KeyBuilder.thumbnailKey(item.filename, item.dateTaken)

                        val bytes = contentResolver.openInputStream(item.mediaStoreUri)?.use {
                            it.readBytes()
                        } ?: throw IOException("Could not open ${item.mediaStoreUri}")

                        if (isVideo) {
                            val videoKey = S3KeyBuilder.videoKey(item.filename, item.dateTaken)

                            uploader.upload(
                                key = videoKey,
                                contentType = videoContentType(item.filename),
                                contentLength = bytes.size.toLong(),
                                body = ByteStream.fromBytes(bytes)
                            ).getOrThrow()

                            val thumbBytes = thumbnailGen.createWebPThumbnailFromVideo(item.mediaStoreUri)
                            uploader.upload(
                                key = thumbKey,
                                contentType = "image/webp",
                                contentLength = thumbBytes.size.toLong(),
                                body = ByteStream.fromBytes(thumbBytes)
                            ).getOrThrow()

                            uploadDao.setUploadedVideoPaths(
                                id = rowId,
                                videoPath = videoKey,
                                thumbnailPath = thumbKey,
                                uploadedAt = System.currentTimeMillis(),
                                compressed = false,
                                originalPathB2 = null
                            )
                        } else {
                            val photoKey = S3KeyBuilder.photoKey(item.filename, item.dateTaken)

                            uploader.upload(
                                key = photoKey,
                                contentType = "image/jpeg",
                                contentLength = bytes.size.toLong(),
                                body = ByteStream.fromBytes(bytes)
                            ).getOrThrow()

                            val thumbBytes = thumbnailGen.createWebPThumbnail(item.mediaStoreUri)
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
                galleryRepository.invalidate()
            } finally {
                inFlight.remove(uriKey)
            }
        }
    }

    private fun videoContentType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    companion object {
        private const val STATUS_REFRESH_INTERVAL_MS = 5000L
    }
}
