package com.hriyaan.photostorage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.data.ShareLinkRecord
import com.hriyaan.photostorage.data.UploadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActiveShareLinksActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_active_share_links)
        title = getString(R.string.share_links_active_title)

        val app = application as PhotoBackupApp
        val recyclerView = findViewById<RecyclerView>(R.id.list)
        recyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val links = withContext(Dispatchers.IO) { app.shareLinkService.activeLinks() }
            val now = System.currentTimeMillis()
            val dao = app.uploadDatabase.dao
            val filenames = withContext(Dispatchers.IO) {
                links.associate { link ->
                    val record = dao.getAll().firstOrNull { it.id == link.uploadId }
                    link.id to (record?.filename ?: "")
                }
            }
            if (links.isEmpty()) {
                findViewById<View>(R.id.empty).visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                findViewById<View>(R.id.empty).visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = ShareLinkAdapter(links, filenames, now)
            }
        }
    }

    private inner class ShareLinkAdapter(
        private val links: List<ShareLinkRecord>,
        private val filenames: Map<Long, String>,
        private val now: Long
    ) : RecyclerView.Adapter<ShareLinkAdapter.VH>() {

        override fun getItemCount(): Int = links.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_share_link, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(links[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val filename: TextView = itemView.findViewById(R.id.filename)
            private val createdAt: TextView = itemView.findViewById(R.id.created_at)
            private val expiresAt: TextView = itemView.findViewById(R.id.expires_at)
            private val actions: View = itemView.findViewById(R.id.actions)
            private val btnCopy: Button = itemView.findViewById(R.id.btn_copy)
            private val btnOpen: Button = itemView.findViewById(R.id.btn_open)

            fun bind(record: ShareLinkRecord) {
                val isExpired = record.expiresAt <= now
                val name = filenames[record.id].orEmpty().ifEmpty { "Unknown" }

                filename.text = if (isExpired) "$name (expired)" else name
                if (isExpired) {
                    filename.alpha = 0.5f
                    createdAt.alpha = 0.5f
                    expiresAt.alpha = 0.5f
                } else {
                    filename.alpha = 1.0f
                    createdAt.alpha = 1.0f
                    expiresAt.alpha = 1.0f
                }

                createdAt.text = "Created ${DateUtils.getRelativeTimeSpanString(record.createdAt)}"
                expiresAt.text = if (isExpired) {
                    "Expired ${DateUtils.getRelativeTimeSpanString(record.expiresAt)}"
                } else {
                    "Expires ${DateUtils.getRelativeTimeSpanString(record.expiresAt)}"
                }

                if (isExpired) {
                    actions.visibility = View.GONE
                } else {
                    actions.visibility = View.VISIBLE
                    btnCopy.setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Share link", record.url))
                        Toast.makeText(this@ActiveShareLinksActivity, R.string.share_link_copied, Toast.LENGTH_SHORT).show()
                    }
                    btnOpen.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.url)))
                    }
                }
            }
        }
    }
}
