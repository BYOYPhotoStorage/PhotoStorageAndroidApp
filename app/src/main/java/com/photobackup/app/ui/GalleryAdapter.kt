package com.photobackup.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photobackup.app.R
import com.photobackup.app.data.UploadDao
import com.photobackup.app.databinding.ItemGalleryBinding

class GalleryAdapter(
    private val onTap: (GalleryItem) -> Unit,
    private val onLongPress: (GalleryItem) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    private val items = mutableListOf<GalleryItem>()

    fun submit(newItems: List<GalleryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateItem(photoId: Long, replacement: GalleryItem) {
        val idx = items.indexOfFirst { it.photo.id == photoId }
        if (idx >= 0) {
            items[idx] = replacement
            notifyItemChanged(idx)
        }
    }

    fun findByPhotoId(photoId: Long): GalleryItem? =
        items.firstOrNull { it.photo.id == photoId }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGalleryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemGalleryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GalleryItem) {
            Glide.with(binding.photoImage)
                .load(item.photo.uri)
                .centerCrop()
                .into(binding.photoImage)

            val status = item.record?.status
            binding.progress.isVisible = status == UploadDao.STATUS_UPLOADING
            when (status) {
                UploadDao.STATUS_UPLOADED -> {
                    binding.statusIcon.setImageResource(R.drawable.ic_cloud_uploaded)
                    binding.statusIcon.isVisible = true
                }
                UploadDao.STATUS_FAILED -> {
                    binding.statusIcon.setImageResource(R.drawable.ic_upload_failed)
                    binding.statusIcon.isVisible = true
                }
                else -> binding.statusIcon.isVisible = false
            }

            binding.root.setOnClickListener { onTap(item) }
            binding.root.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }
    }
}
