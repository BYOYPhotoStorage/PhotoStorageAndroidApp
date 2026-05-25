package com.hriyaan.photostorage.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.b2.S3ClientFactory
import com.hriyaan.photostorage.b2.S3Config
import com.hriyaan.photostorage.b2.S3Uploader
import com.hriyaan.photostorage.databinding.ActivityDetailBinding
import com.hriyaan.photostorage.gallery.GalleryViewMode
import com.hriyaan.photostorage.thumbnail.B2ThumbnailFetcher
import kotlinx.coroutines.launch
import java.io.File

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var adapter: DetailAdapter
    private var s3Uploader: S3Uploader? = null

    private val app: PhotoBackupApp get() = application as PhotoBackupApp
    private val prefsStore by lazy { app.prefsStore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        val startItemId = intent.getStringExtra(EXTRA_START_ITEM_ID) ?: run {
            finish()
            return
        }
        val modeKey = intent.getStringExtra(EXTRA_VIEW_MODE) ?: GalleryViewMode.MERGED.key
        val mode = GalleryViewMode.fromKey(modeKey)

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

        val imageLoader = buildImageLoader(uploader)

        adapter = DetailAdapter(imageLoader)
        binding.viewPager.adapter = adapter
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
            }
        })

        binding.backButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.galleryRepository.observe(mode).collect { items ->
                    adapter.submitList(items)
                    val startPos = items.indexOfFirst { it.id == startItemId }
                    if (startPos >= 0) {
                        binding.viewPager.setCurrentItem(startPos, false)
                        updateCounter(startPos)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        s3Uploader?.close()
    }

    private fun updateCounter(position: Int) {
        val count = adapter.itemCount
        if (count > 0) {
            binding.counterText.text = getString(R.string.detail_counter, position + 1, count)
            binding.counterText.isVisible = true
        } else {
            binding.counterText.isVisible = false
        }
    }

    private fun buildImageLoader(uploader: S3Uploader): ImageLoader {
        val ctx = this
        val cacheDir = File(cacheDir, "detail_images").also { it.mkdirs() }
        return ImageLoader.Builder(ctx)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir)
                    .maxSizeBytes(1_000L * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(ctx)
                    .maxSizePercent(0.30)
                    .build()
            }
            .components {
                add(B2ThumbnailFetcher.Factory(ctx, uploader, null))
            }
            .build()
    }

    companion object {
        private const val EXTRA_START_ITEM_ID = "start_item_id"
        private const val EXTRA_VIEW_MODE = "view_mode"

        fun intent(context: Context, startItemId: String, mode: GalleryViewMode): Intent {
            return Intent(context, DetailActivity::class.java).apply {
                putExtra(EXTRA_START_ITEM_ID, startItemId)
                putExtra(EXTRA_VIEW_MODE, mode.key)
            }
        }
    }
}
