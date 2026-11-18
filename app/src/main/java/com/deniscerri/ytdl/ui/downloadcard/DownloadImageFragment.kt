package com.deniscerri.ytdl.ui.downloadcard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.GalleryDlUtil
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * YTindir: the Image tab. Everything in the post (all images of a carousel or gallery) is downloaded
 * with gallery-dl; videos only when asked, as the Video tab gives them in better quality.
 */
class DownloadImageFragment(private val resultItem: ResultItem? = null, private var currentDownloadItem: DownloadItem? = null) : Fragment(), GUISync {
    private lateinit var downloadViewModel: DownloadViewModel
    private var saveDir: TextInputLayout? = null
    private var freeSpace: TextView? = null

    lateinit var downloadItem: DownloadItem

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        return inflater.inflate(R.layout.fragment_download_image, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        lifecycleScope.launch {
            downloadItem = withContext(Dispatchers.IO) {
                val current = currentDownloadItem
                if (current != null && current.type == DownloadType.image) {
                    Gson().fromJson(Gson().toJson(current, DownloadItem::class.java), DownloadItem::class.java)
                } else {
                    downloadViewModel.createDownloadItemFromResult(resultItem, current?.url ?: "", DownloadType.image).apply {
                        if (current != null) {
                            title = current.title
                            author = current.author
                        }
                    }
                }
            }
            // an image download never adds an audio download next to it
            downloadItem.videoPreferences.alsoDownloadAsAudio = false

            view.findViewById<MaterialSwitch>(R.id.image_include_videos).apply {
                isChecked = GalleryDlUtil.includesVideos(downloadItem.format)
                setOnCheckedChangeListener { _, checked ->
                    downloadItem.format = GalleryDlUtil.imageFormat(checked)
                    preferences.edit { putBoolean(GalleryDlUtil.INCLUDE_VIDEOS_KEY, checked) }
                }
            }

            saveDir = view.findViewById<TextInputLayout>(R.id.outputPath).apply {
                editText!!.setText(FileUtil.formatPath(downloadItem.downloadPath))
                editText!!.isFocusable = false
                editText!!.isClickable = true
                editText!!.setOnClickListener {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    pathResultLauncher.launch(intent)
                }
            }

            freeSpace = view.findViewById(R.id.freespace)
            showFreeSpace()
        }
    }

    private fun showFreeSpace() {
        if (!::downloadItem.isInitialized) return
        val free = FileUtil.convertFileSize(File(FileUtil.formatPath(downloadItem.downloadPath)).freeSpace)
        freeSpace?.text = getString(R.string.freespace) + ": " + free
        freeSpace?.visibility = if (free == "?") View.GONE else View.VISIBLE
    }

    private val pathResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            activity?.contentResolver?.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            downloadItem.downloadPath = uri.toString()
            saveDir?.editText?.setText(FileUtil.formatPath(uri.toString()), TextView.BufferType.EDITABLE)
            showFreeSpace()
        }
    }

    override fun updateTitleAuthor(t: String, a: String) {
        if (!::downloadItem.isInitialized) return
        downloadItem.title = t
        downloadItem.author = a
    }

    override fun updateUI(res: ResultItem?) {
        if (!::downloadItem.isInitialized || res == null) return
        if (downloadItem.title.isBlank()) downloadItem.title = res.title
        if (downloadItem.author.isBlank()) downloadItem.author = res.author
        if (downloadItem.thumb.isBlank()) downloadItem.thumb = res.thumb
    }
}
