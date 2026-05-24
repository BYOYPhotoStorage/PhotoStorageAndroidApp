package com.hriyaan.photostorage.ui

import android.view.LayoutInflater
import android.view.ViewGroup
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
    private val imageLoader: ImageLoader,
    private val onPlayVideo: (GalleryItem) -> Unit
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

    inner class VH(private val binding: ItemDetailPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GalleryItem) {
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

            binding.videoPlayButton.isVisible = isVideo
            if (isVideo) {
                binding.videoPlayButton.setOnClickListener { onPlayVideo(item) }
            } else {
                binding.videoPlayButton.setOnClickListener(null)
            }
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
