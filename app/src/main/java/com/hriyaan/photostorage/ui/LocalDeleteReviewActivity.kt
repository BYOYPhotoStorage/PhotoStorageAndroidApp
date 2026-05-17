package com.hriyaan.photostorage.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.data.UploadRecord
import com.hriyaan.photostorage.gallery.ThumbnailSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.CharacterIterator
import java.text.StringCharacterIterator

class LocalDeleteReviewActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onUserApproved()
        else onUserCancelled()
    }

    private lateinit var adapter: PendingDeleteAdapter
    private var pendingSelected: List<UploadRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_delete_review)

        val app = application as PhotoBackupApp
        val dao = app.uploadDatabase.dao

        adapter = PendingDeleteAdapter()
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@LocalDeleteReviewActivity)
            adapter = this@LocalDeleteReviewActivity.adapter
        }

        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { dao.getPendingLocalDelete() }
            val totalSize = rows.sumOf { it.size }
            findViewById<TextView>(R.id.subhead).text = getString(
                R.string.local_delete_review_subhead,
                rows.size,
                humanReadableByteCountBin(totalSize)
            )
            adapter.submitList(rows)
        }

        findViewById<Button>(R.id.confirm).setOnClickListener { onConfirm() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
    }

    private fun onConfirm() {
        val checked = adapter.getCheckedItems()
        val unchecked = adapter.getUncheckedItems()

        if (checked.isEmpty()) {
            finish()
            return
        }

        val app = application as PhotoBackupApp
        val dao = app.uploadDatabase.dao

        lifecycleScope.launch(Dispatchers.IO) {
            for (row in unchecked) {
                dao.clearPendingLocalDelete(row.id)
            }
        }

        val uris = checked.map { Uri.parse(it.localUri) }
        val intentSender = MediaStore.createDeleteRequest(contentResolver, uris).intentSender
        launcher.launch(IntentSenderRequest.Builder(intentSender).build())
        pendingSelected = checked
    }

    private fun onUserApproved() {
        val app = application as PhotoBackupApp
        val dao = app.uploadDatabase.dao
        lifecycleScope.launch(Dispatchers.IO) {
            for (row in pendingSelected) {
                dao.setLocalPresent(row.id, false)
                dao.clearPendingLocalDelete(row.id)
            }
            app.prefsStore.setLocalDeleteDismissStreak(0)
            app.prefsStore.setLocalDeleteSuppressUntil(null)
            app.galleryRepository.invalidate()
        }
        Toast.makeText(this, R.string.local_delete_confirm, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun onUserCancelled() {
        finish()
    }

    private fun humanReadableByteCountBin(bytes: Long): String {
        val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(bytes)
        if (absB < 1024) return "$bytes B"
        var value = absB
        val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
        var i = 40
        while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
            value = value shr 10
            ci.next()
            i -= 10
        }
        value *= if (bytes < 0) -1 else 1
        return String.format(java.util.Locale.US, "%.1f %ciB", value / 1024.0, ci.current())
    }

    private inner class PendingDeleteAdapter : RecyclerView.Adapter<PendingDeleteAdapter.VH>() {

        private var items: List<UploadRecord> = emptyList()
        private val checkedIds = mutableSetOf<Long>()

        fun submitList(list: List<UploadRecord>) {
            items = list
            checkedIds.clear()
            checkedIds.addAll(list.map { it.id })
            notifyDataSetChanged()
        }

        fun getCheckedItems(): List<UploadRecord> = items.filter { it.id in checkedIds }
        fun getUncheckedItems(): List<UploadRecord> = items.filter { it.id !in checkedIds }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_local_delete_review, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
            private val filename: TextView = itemView.findViewById(R.id.filename)
            private val details: TextView = itemView.findViewById(R.id.details)
            private val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)

            fun bind(record: UploadRecord) {
                filename.text = record.filename
                val sizeStr = humanReadableByteCountBin(record.size)
                val uploadedRel = record.uploadedAt?.let {
                    DateUtils.getRelativeTimeSpanString(it)
                } ?: ""
                details.text = "$sizeStr · $uploadedRel"

                checkbox.isChecked = record.id in checkedIds
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) checkedIds.add(record.id)
                    else checkedIds.remove(record.id)
                }

                val context = itemView.context
                val localFile = File(Uri.parse(record.localUri).path ?: "")
                val data: Any = if (localFile.exists()) {
                    Uri.parse(record.localUri)
                } else {
                    ThumbnailSource.B2Path(record.thumbnailB2Path.orEmpty())
                }

                val request = ImageRequest.Builder(context)
                    .data(data)
                    .placeholder(R.drawable.thumbnail_placeholder)
                    .target(thumbnail)
                    .build()
                ImageLoader(context).enqueue(request)
            }
        }
    }
}
