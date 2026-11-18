package com.deniscerri.ytdl.ui.more.settings.updating.gallerydl

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.GalleryDl
import com.deniscerri.ytdl.ui.more.settings.BaseSettingsFragment
import com.deniscerri.ytdl.ui.more.settings.SettingsRegistry
import com.deniscerri.ytdl.util.UiUtil


class GalleryDlUpdateSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.gallery_dl

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val preferenceXMLRes = R.xml.gallery_dl_update_preferences
        setPreferencesFromResource(preferenceXMLRes, rootKey)
        SettingsRegistry.bindFragment(this, preferenceXMLRes)

        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        findPreference<Preference>("reset_preferences")?.setOnPreferenceClickListener {
            UiUtil.showGenericConfirmDialog(requireContext(), getString(R.string.reset), getString(R.string.reset_preferences_in_screen)) {
                resetPreferences(preferences.edit().remove(GalleryDl.SOURCE_KEY), preferenceXMLRes)
                requireActivity().recreate()
                findNavController().currentDestination?.id?.apply {
                    findNavController().popBackStack(this,true)
                    findNavController().navigate(this)
                }
            }
            true
        }
    }
}
