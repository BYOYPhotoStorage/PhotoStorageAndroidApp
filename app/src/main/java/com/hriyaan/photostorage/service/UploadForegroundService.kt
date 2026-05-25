package com.hriyaan.photostorage.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3KeyBuilder
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.MediaStorePhoto
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.dedup.DuplicateDetector
import com.hriyaan.photostorage.gallery.GalleryRepository
import com.hriyaan.photostorage.media.MediaItem
import com.hriyaan.photostorage.media.MediaStoreScanner
import com.hriyaan.photostorage.media.MediaType
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.thumbnail.ThumbnailGen
import com.hriyaan.photostorage.worker.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UploadForegroundService : Service() {

    private lateinit var uploadDao: UploadDao
    private lateinit var prefsStore: PrefsStore
    private lateinit var notificationManager: UploadNotificationManager
    private lateinit var duplicateDetector: DuplicateDetector
    private lateinit var galleryRepository: GalleryRepository
    private lateinit var scanner: MediaStoreScanner
    private var uploadWorker: UploadWorker? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val processLock = Mutex()
    private var isProcessing = false

    private val debounceRunnable = Runnable {
        scope.launch(Dispatchers.IO) {
            handleMediaChange()
        }
    }

    private val imageObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleDebouncedScan()
        }
    }

    private var videoObserver: ContentObserver? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_VIDEOS_ENABLED) {
            if (prefsStore.getVideosEnabled()) registerVideoObserver()
            else unregisterVideoObserver()
        }
    }

    override fun onCreate() {
        super.onCreate()

        val app = application as PhotoBackupApp
        uploadDao = app.uploadDatabase.dao
        prefsStore = app.prefsStore
        notificationManager = UploadNotificationManager(this)
        duplicateDetector = DuplicateDetector(this, uploadDao)
        galleryRepository = app.galleryRepository
        scanner = MediaStoreScanner(this)

        val creds = prefsStore.getCredentials()
        if (creds != null) {
            val config = S3Config.forBucket(creds.bucketName)
            val client = S3ClientFactory.create(creds, config)
            val s3Uploader = S3Uploader(client, creds.bucketName)
            uploadWorker = UploadWorker(
                this,
                uploadDao,
                s3Uploader,
                ThumbnailGen(this),
                notificationManager
            )
        }

        notificationManager.ensureChannels()

        val pendingCount = uploadDao.getPendingQueue().size
        val notification = notificationManager.buildForegroundNotification(pendingCount)
        startForeground(UploadNotificationManager.ID_FOREGROUND, notification)

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            imageObserver
        )
        if (prefsStore.getVideosEnabled()) registerVideoObserver()
        prefsStore.registerOnChangedListener(prefsListener)

        if (prefsStore.getLastScanTimestamp() == 0L) {
            scope.launch(Dispatchers.IO) {
                handleMediaChange()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROCESS_QUEUE) {
            triggerWorker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(imageObserver)
        unregisterVideoObserver()
        prefsStore.unregisterOnChangedListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerVideoObserver() {
        if (videoObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleDebouncedScan()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        videoObserver = observer
    }

    private fun unregisterVideoObserver() {
        videoObserver?.let { contentResolver.unregisterContentObserver(it) }
        videoObserver = null
    }

    private fun scheduleDebouncedScan() {
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    private suspend fun handleMediaChange() {
        val lastScan = prefsStore.getLastScanTimestamp()
        val sinceArg = lastScan.takeIf { it > 0L }
        val selectedBuckets = prefsStore.getSelectedBucketIds()
        val items = scanner.scanImages(sinceArg, bucketIds = selectedBuckets) +
            scanner.scanVideos(sinceArg, bucketIds = selectedBuckets)

        var newestTimestamp = lastScan
        for (item in items) {
            val dupResult = duplicateDetector.isDuplicate(item.toMediaStorePhoto())
            if (dupResult is com.hriyaan.photostorage.dedup.DuplicateResult.Duplicate) {
                continue
            }

            val photoPath = S3KeyBuilder.photoKey(item.filename, item.dateTaken)
            val thumbPath = S3KeyBuilder.thumbnailKey(item.filename, item.dateTaken)

            uploadDao.insert(
                UploadRecord(
                    localUri = item.uri.toString(),
                    filename = item.filename,
                    size = item.size,
                    dateTaken = item.dateTaken,
                    photoB2Path = photoPath,
                    thumbnailB2Path = thumbPath,
                    status = UploadDao.STATUS_PENDING,
                    uploadedAt = null,
                    mediaType = item.mediaType.toDbValue()
                )
            )

            if (item.dateTaken > newestTimestamp) {
                newestTimestamp = item.dateTaken
            }
        }

        if (items.isEmpty()) {
            prefsStore.setLastScanTimestamp(System.currentTimeMillis())
        } else {
            prefsStore.setLastScanTimestamp(newestTimestamp)
            galleryRepository.invalidate()
        }

        triggerWorker()
    }

    private fun MediaItem.toMediaStorePhoto(): MediaStorePhoto = MediaStorePhoto(
        id = 0L,
        uri = uri,
        filename = filename,
        size = size,
        dateTakenMs = dateTaken
    )

    private fun triggerWorker() {
        val worker = uploadWorker ?: return
        scope.launch(Dispatchers.IO) {
            processLock.withLock {
                if (isProcessing) return@withLock
                isProcessing = true
                try {
                    worker.processQueue()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 3000L
        private const val KEY_VIDEOS_ENABLED = "videos_enabled"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UploadForegroundService::class.java)
            )
        }

        fun processQueueNow(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UploadForegroundService::class.java).setAction(ACTION_PROCESS_QUEUE)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UploadForegroundService::class.java))
        }

        private const val ACTION_PROCESS_QUEUE = "com.hriyaan.photostorage.action.PROCESS_QUEUE"
    }
}
