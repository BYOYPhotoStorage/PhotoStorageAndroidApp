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
import com.hriyaan.photostorage.databinding.ItemGalleryBinding
import com.hriyaan.photostorage.gallery.GalleryItem
import com.hriyaan.photostorage.gallery.ThumbnailSource

class GalleryAdapter(
    private val imageLoader: ImageLoader,
    private val onTap: (GalleryItem) -> Unit,
    private val onLongPress: (GalleryItem) -> Unit
) : ListAdapter<GalleryItem, GalleryAdapter.VH>(DIFF) {

    private val selectedIds = mutableSetOf<String>()

    fun findById(id: String): GalleryItem? = currentList.firstOrNull { it.id == id }

    fun setSelection(ids: Set<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGalleryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), selectedIds.contains(getItem(position).id))
    }

    inner class VH(private val binding: ItemGalleryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GalleryItem, selected: Boolean) {
            val context = binding.root.context
            val source = item.thumbnailSource
            val data: Any = when (source) {
                is ThumbnailSource.LocalUri -> source.uri
                is ThumbnailSource.B2Path -> source
            }
            val request = ImageRequest.Builder(context)
                .data(data)
                .placeholder(R.drawable.thumbnail_placeholder)
                .error(R.drawable.thumbnail_error)
                .target(binding.photoImage)
                .build()
            imageLoader.enqueue(request)

            val queued = (item as? GalleryItem.LocalOnly)?.queuedRecord
            binding.progress.isVisible = queued?.status == UploadDao.STATUS_UPLOADING
            binding.badge.setImageResource(badgeFor(item))
            binding.selectionScrim.isVisible = selected

            binding.root.setOnClickListener { onTap(item) }
            binding.root.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }

        private fun badgeFor(item: GalleryItem): Int = when (item) {
            is GalleryItem.Synced -> R.drawable.ic_badge_synced
            is GalleryItem.CloudOnly -> R.drawable.ic_badge_cloud_only
            is GalleryItem.LocalOnly -> when (item.queuedRecord?.status) {
                UploadDao.STATUS_FAILED, UploadDao.STATUS_PERMANENTLY_FAILED -> R.drawable.ic_upload_failed
                UploadDao.STATUS_UPLOADED -> R.drawable.ic_cloud_uploaded
                else -> R.drawable.ic_badge_local_only
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
