package com.hriyaan.photostorage

import android.app.Application
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.data.PrefsStore
import com.hriyaan.photostorage.data.ShareLinkDao
import com.hriyaan.photostorage.data.UploadDatabase
import com.hriyaan.photostorage.gallery.DeletionEngine
import com.hriyaan.photostorage.gallery.GalleryRepository
import com.hriyaan.photostorage.gallery.MediaStoreDeleteLauncher
import com.hriyaan.photostorage.notification.UploadNotificationManager
import com.hriyaan.photostorage.recovery.IndexRecoveryService
import com.hriyaan.photostorage.share.ShareLinkService
import com.hriyaan.photostorage.thumbnail.ThumbnailCacheFactory
import com.hriyaan.photostorage.worker.IndexSyncScheduler
import com.hriyaan.photostorage.worker.LocalDeleteScheduler
import com.hriyaan.photostorage.worker.SoftDeleteCleanupScheduler

class PhotoBackupApp : Application() {

    lateinit var prefsStore: PrefsStore
        private set

    lateinit var uploadDatabase: UploadDatabase
        private set

    lateinit var s3Uploader: S3Uploader
        private set

    lateinit var galleryRepository: GalleryRepository
        private set

    lateinit var deletionEngine: DeletionEngine
        private set

    lateinit var thumbnailCacheFactory: ThumbnailCacheFactory
        private set

    lateinit var indexRecoveryService: IndexRecoveryService
        private set

    lateinit var shareLinkDao: ShareLinkDao
        private set

    lateinit var shareLinkService: ShareLinkService
        private set

    override fun onCreate() {
        super.onCreate()

        prefsStore = PrefsStore(this)
        uploadDatabase = UploadDatabase(this)
        UploadNotificationManager(this).ensureChannels()

        galleryRepository = GalleryRepository(this, uploadDatabase.dao, prefsStore)

        val creds = prefsStore.getCredentials()
        if (creds != null) {
            val client = S3ClientFactory.create(creds, S3Config.forBucket(creds.bucketName))
            s3Uploader = S3Uploader(client, creds.bucketName)
            deletionEngine = DeletionEngine(
                this, uploadDatabase.dao, s3Uploader, galleryRepository,
                MediaStoreDeleteLauncher { false }
            )
            thumbnailCacheFactory = ThumbnailCacheFactory(
                context = this,
                s3Uploader = s3Uploader,
                prefsStore = prefsStore,
                egressRecorder = { bytes ->
                    prefsStore.setEgressBytesMonth(prefsStore.getEgressBytesMonth() + bytes)
                }
            )
            indexRecoveryService = IndexRecoveryService(
                this, s3Uploader, uploadDatabase.dao, uploadDatabase, prefsStore
            )
            shareLinkDao = uploadDatabase.shareLinkDao
            shareLinkService = ShareLinkService(s3Uploader, shareLinkDao)
        }

        IndexSyncScheduler.schedule(this)
        SoftDeleteCleanupScheduler.schedule(this)
        LocalDeleteScheduler.schedule(this)

        if (prefsStore.hasCredentials() && prefsStore.isAutoUploadEnabled() &&
            !prefsStore.hasCompletedFirstBackupFlow()) {
            prefsStore.setFirstBackupScope("today")
            prefsStore.setVideosEnabled(false)
            prefsStore.setFirstBackupFlowCompleted(true)
        }
    }
}
