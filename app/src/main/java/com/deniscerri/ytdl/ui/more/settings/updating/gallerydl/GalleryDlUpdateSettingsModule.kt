package com.deniscerri.ytdl.ui.more.settings.updating.gallerydl

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.GalleryDl
import com.deniscerri.ytdl.ui.more.settings.SettingHost
import com.deniscerri.ytdl.ui.more.settings.SettingModule
import com.deniscerri.ytdl.ui.more.settings.updating.UpdateChannelPreference
import com.deniscerri.ytdl.ui.more.settings.updating.UpdateStatusCardPreference
import com.deniscerri.ytdl.util.UiUtil
import com.deniscerri.ytdl.util.UpdateUtil
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** YTindir: the gallery-dl update screen, next to yt-dlp's */
object GalleryDlUpdateSettingsModule : SettingModule {

    override fun bindLogic(pref: Preference, host: SettingHost) {
        val context = pref.context
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        when(pref.key) {
            "gallery_dl_status_card" -> {
                (pref as UpdateStatusCardPreference).apply {
                    status = restingStatus(preferences.getBoolean(GalleryDl.AUTO_UPDATE_KEY, true))
                    onButtonClick = { checkForUpdates(context, host, preferences) }
                    version = GalleryDl.version(context)
                    subtitle = channelName(context)
                    detail = UpdateStatusCardPreference.buildDetail(context, preferences.getLong(GalleryDl.LAST_CHECK_KEY, 0L))
                }
            }
            "gallery_dl_channel" -> {
                val titles = context.getStringArray(R.array.gallery_dl_source)
                val values = context.getStringArray(R.array.gallery_dl_source_values)
                (pref as UpdateChannelPreference).apply {
                    setChannels(values.zip(titles))
                    selectedChannel = GalleryDl.currentChannel(context)
                    onChannelSelected = { value ->
                        preferences.edit { putString(GalleryDl.SOURCE_KEY, value) }
                        selectedChannel = value
                        checkForUpdates(context, host, preferences)
                    }
                }
            }
            GalleryDl.AUTO_UPDATE_KEY -> {
                pref.setOnPreferenceChangeListener { _, newValue ->
                    (host.findPref("gallery_dl_status_card") as? UpdateStatusCardPreference)?.apply {
                        if (status == UpdateStatusCardPreference.Status.UP_TO_DATE || status == UpdateStatusCardPreference.Status.UNKNOWN) {
                            status = restingStatus(newValue as Boolean)
                        }
                    }
                    true
                }
            }
        }
    }

    private fun restingStatus(autoUpdate: Boolean) =
        if (autoUpdate) UpdateStatusCardPreference.Status.UP_TO_DATE else UpdateStatusCardPreference.Status.UNKNOWN

    fun channelName(context: Context): String {
        val titles = context.getStringArray(R.array.gallery_dl_source)
        val values = context.getStringArray(R.array.gallery_dl_source_values)
        val index = values.indexOf(GalleryDl.currentChannel(context))
        return if (index >= 0) titles[index] else titles[0]
    }

    private fun refreshDetails(context: Context, host: SettingHost, preferences: SharedPreferences) {
        (host.findPref("gallery_dl_status_card") as? UpdateStatusCardPreference)?.apply {
            version = GalleryDl.version(context)
            subtitle = channelName(context)
            detail = UpdateStatusCardPreference.buildDetail(context, preferences.getLong(GalleryDl.LAST_CHECK_KEY, 0L))
        }
    }

    private fun checkForUpdates(context: Context, host: SettingHost, preferences: SharedPreferences) {
        val card = host.findPref("gallery_dl_status_card") as? UpdateStatusCardPreference

        host.hostLifecycleOwner.lifecycleScope.launch {
            val view = host.hostView
            card?.status = UpdateStatusCardPreference.Status.CHECKING

            val res = UpdateUtil(context).updateGalleryDl()
            when (res.status) {
                UpdateUtil.YTDLPUpdateStatus.DONE -> {
                    if (view != null && view.isAttachedToWindow) {
                        Snackbar.make(view, res.message, Snackbar.LENGTH_LONG).show()
                    }
                    card?.status = UpdateStatusCardPreference.Status.UP_TO_DATE
                }
                UpdateUtil.YTDLPUpdateStatus.ALREADY_UP_TO_DATE -> {
                    card?.status = UpdateStatusCardPreference.Status.UP_TO_DATE
                }
                UpdateUtil.YTDLPUpdateStatus.ERROR -> {
                    card?.status = UpdateStatusCardPreference.Status.ERROR
                    showErrorSnackbar(view, host, res.message.ifBlank { context.getString(R.string.errored) })
                }
                UpdateUtil.YTDLPUpdateStatus.PROCESSING -> {
                    card?.status = restingStatus(preferences.getBoolean(GalleryDl.AUTO_UPDATE_KEY, true))
                }
            }

            refreshDetails(context, host, preferences)
        }
    }

    private fun showErrorSnackbar(view: View?, host: SettingHost, msg: String) {
        if (view == null || !view.isAttachedToWindow) return
        val snackBar = Snackbar.make(view, msg, Snackbar.LENGTH_LONG)
        snackBar.setAction(R.string.copy_log){
            UiUtil.copyToClipboard(msg, host.getHostContext())
        }
        val snackTextView = snackBar.view.findViewById<View>(com.google.android.material.R.id.snackbar_text) as TextView
        snackTextView.maxLines = 9999999
        snackBar.show()
    }
}
