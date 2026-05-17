package com.hriyaan.photostorage.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.hriyaan.photostorage.PhotoBackupApp
import com.hriyaan.photostorage.R
import com.hriyaan.photostorage.gallery.GalleryItem
import com.hriyaan.photostorage.gallery.GalleryRepository
import com.hriyaan.photostorage.gallery.GalleryViewMode
import com.hriyaan.photostorage.share.ShareLinkService
import com.hriyaan.photostorage.share.ShareLinkTtl
import kotlinx.coroutines.launch

class ShareLinkDialog : DialogFragment() {

    companion object {
        const val ARG_ITEM_ID = "item_id"
        fun newInstance(itemId: String) = ShareLinkDialog().apply {
            arguments = bundleOf(ARG_ITEM_ID to itemId)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_share_link, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(view).create()
        view.findViewById<Button>(R.id.cancel).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.create).setOnClickListener { onCreate(view) }
        return dialog
    }

    private fun onCreate(view: View) {
        val ttl = when (view.findViewById<RadioGroup>(R.id.ttl_group).checkedRadioButtonId) {
            R.id.ttl_24h -> ShareLinkTtl.ONE_DAY
            R.id.ttl_7d -> ShareLinkTtl.ONE_WEEK
            else -> ShareLinkTtl.ONE_HOUR
        }
        val itemId = requireArguments().getString(ARG_ITEM_ID)!!
        val app = requireActivity().application as PhotoBackupApp

        lifecycleScope.launch {
            val item = app.galleryRepository.findItemById(itemId)
            if (item == null) {
                Toast.makeText(requireContext(), R.string.share_link_error_not_found, Toast.LENGTH_SHORT).show()
                dismiss()
                return@launch
            }
            val result = app.shareLinkService.createLink(item, ttl)
            result
                .onSuccess { record ->
                    copyToClipboard(record.url)
                    val label = requireContext().getString(ttl.labelRes)
                    Toast.makeText(requireContext(), getString(R.string.share_link_created, label), Toast.LENGTH_SHORT).show()
                    startActivity(buildShareIntent(record.url))
                }
                .onFailure {
                    Toast.makeText(requireContext(), R.string.share_link_error_generic, Toast.LENGTH_SHORT).show()
                }
            dismiss()
        }
    }

    private fun copyToClipboard(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Share link", url))
    }

    private fun buildShareIntent(url: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            },
            getString(R.string.share_link_chooser_title)
        )
}

suspend fun GalleryRepository.findItemById(id: String): GalleryItem? =
    load(GalleryViewMode.MERGED).firstOrNull { it.id == id }
