package com.hriyaan.photostorage.service

import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
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

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(debounceRunnable)
            handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val app = application as PhotoBackupApp
        uploadDao = app.uploadDatabase.dao
        prefsStore = app.prefsStore
        notificationManager = UploadNotificationManager(this)
        duplicateDetector = DuplicateDetector(this, uploadDao)

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
            contentObserver
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(contentObserver)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun handleMediaChange() {
        val lastScan = prefsStore.getLastScanTimestamp()
        val photos = queryNewPhotos(lastScan)

        var newestTimestamp = lastScan
        for (photo in photos) {
            val dupResult = duplicateDetector.isDuplicate(photo)
            if (dupResult is com.hriyaan.photostorage.dedup.DuplicateResult.Duplicate) {
                continue
            }

            val photoPath = S3KeyBuilder.photoKey(photo.filename, photo.dateTakenMs)
            val thumbPath = S3KeyBuilder.thumbnailKey(photo.filename, photo.dateTakenMs)

            uploadDao.insert(
                UploadRecord(
                    localUri = photo.uri.toString(),
                    filename = photo.filename,
                    size = photo.size,
                    dateTaken = photo.dateTakenMs,
                    photoB2Path = photoPath,
                    thumbnailB2Path = thumbPath,
                    status = UploadDao.STATUS_PENDING,
                    uploadedAt = null
                )
            )

            if (photo.dateTakenMs > newestTimestamp) {
                newestTimestamp = photo.dateTakenMs
            }
        }

        if (photos.isEmpty()) {
            prefsStore.setLastScanTimestamp(System.currentTimeMillis())
        } else {
            prefsStore.setLastScanTimestamp(newestTimestamp)
        }

        triggerWorker()
    }

    private fun queryNewPhotos(lastScanTimestamp: Long): List<MediaStorePhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN
        )

        val sinceSeconds = lastScanTimestamp / 1000
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DATE_ADDED} > ?",
            arrayOf(sinceSeconds.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ) ?: return emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            val out = ArrayList<MediaStorePhoto>(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val filename = c.getString(nameCol) ?: continue
                val size = c.getLong(sizeCol)
                val dateTaken = if (c.isNull(dateCol)) 0L else c.getLong(dateCol)
                out += MediaStorePhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    filename = filename,
                    size = size,
                    dateTakenMs = dateTaken
                )
            }
            return out
        }
    }

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

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UploadForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UploadForegroundService::class.java))
        }
    }
}
