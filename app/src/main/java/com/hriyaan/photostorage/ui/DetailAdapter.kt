package com.hriyaan.photostorage.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.data.UploadDao
import com.hriyaan.photostorage.databinding.ItemDetailPageBinding
import com.hriyaan.photostorage.gallery.GalleryItem
import com.hriyaan.photostorage.gallery.ThumbnailSource

class DetailAdapter(
    private val imageLoader: ImageLoader
) : ListAdapter<GalleryItem, DetailAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDetailPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: VH) {
        holder.release()
        super.onViewRecycled(holder)
    }

    inner class VH(private val binding: ItemDetailPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var mediaController: MediaController? = null
        private var currentItem: GalleryItem? = null

        init {
            binding.videoView.setOnCompletionListener {
                resetVideoState()
            }
            binding.videoView.setOnErrorListener { _, _, _ ->
                Toast.makeText(binding.root.context, R.string.video_playback_error, Toast.LENGTH_SHORT).show()
                resetVideoState()
                true
            }
        }

        fun bind(item: GalleryItem) {
            currentItem = item
            release()

            val context = binding.root.context
            val isVideo = isVideo(item)

            val data: Any = when (item) {
                is GalleryItem.LocalOnly -> item.mediaStoreUri
                is GalleryItem.Synced -> item.mediaStoreUri
                is GalleryItem.CloudOnly -> {
                    val path = item.uploadRecord.photoB2Path
                    if (path != null) {
                        ThumbnailSource.B2Path(path)
                    } else {
                        item.thumbnailSource
                    }
                }
            }

            val request = ImageRequest.Builder(context)
                .data(data)
                .placeholder(R.drawable.thumbnail_placeholder)
                .error(R.drawable.thumbnail_error)
                .target(binding.photoView)
                .build()
            imageLoader.enqueue(request)

            binding.photoView.isVisible = true
            binding.videoView.isVisible = false
            binding.videoPlayButton.isVisible = isVideo

            if (isVideo) {
                val uri = videoUri(item)
                if (uri != null) {
                    binding.videoPlayButton.setOnClickListener { startVideo(uri) }
                } else {
                    binding.videoPlayButton.setOnClickListener {
                        Toast.makeText(context, R.string.video_cloud_playback, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                binding.videoPlayButton.setOnClickListener(null)
            }
        }

        private fun startVideo(uri: Uri) {
            binding.videoPlayButton.isVisible = false
            binding.photoView.isVisible = false
            binding.videoView.isVisible = true

            val ctx = binding.root.context
            mediaController = MediaController(ctx).apply {
                setAnchorView(binding.videoView)
            }
            binding.videoView.setMediaController(mediaController)
            binding.videoView.setOnPreparedListener {
                binding.videoView.start()
                mediaController?.show(0)
            }
            binding.videoView.requestFocus()
            binding.videoView.setVideoURI(uri)
        }

        private fun resetVideoState() {
            binding.videoView.stopPlayback()
            binding.videoView.setMediaController(null)
            mediaController = null
            binding.videoView.isVisible = false
            binding.photoView.isVisible = true
            val isVideo = currentItem?.let { isVideo(it) } ?: false
            binding.videoPlayButton.isVisible = isVideo
        }

        fun release() {
            binding.videoView.stopPlayback()
            binding.videoView.setMediaController(null)
            mediaController = null
        }

        private fun videoUri(item: GalleryItem): Uri? = when (item) {
            is GalleryItem.LocalOnly -> item.mediaStoreUri
            is GalleryItem.Synced -> item.mediaStoreUri
            is GalleryItem.CloudOnly -> null
        }

        private fun isVideo(item: GalleryItem): Boolean = when (item) {
            is GalleryItem.LocalOnly -> {
                item.queuedRecord?.mediaType == UploadDao.MEDIA_TYPE_VIDEO ||
                    item.filename.endsWith(".mp4", ignoreCase = true) ||
                    item.filename.endsWith(".mov", ignoreCase = true) ||
                    item.filename.endsWith(".webm", ignoreCase = true) ||
                    item.filename.endsWith(".3gp", ignoreCase = true)
            }
            is GalleryItem.Synced -> {
                item.uploadRecord.mediaType == UploadDao.MEDIA_TYPE_VIDEO ||
                    item.filename.endsWith(".mp4", ignoreCase = true) ||
                    item.filename.endsWith(".mov", ignoreCase = true) ||
                    item.filename.endsWith(".webm", ignoreCase = true) ||
                    item.filename.endsWith(".3gp", ignoreCase = true)
            }
            is GalleryItem.CloudOnly -> {
                item.uploadRecord.mediaType == UploadDao.MEDIA_TYPE_VIDEO ||
                    item.filename.endsWith(".mp4", ignoreCase = true) ||
                    item.filename.endsWith(".mov", ignoreCase = true) ||
                    item.filename.endsWith(".webm", ignoreCase = true) ||
                    item.filename.endsWith(".3gp", ignoreCase = true)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GalleryItem>() {
            override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean =
                oldItem == newItem
        }
    }
}
