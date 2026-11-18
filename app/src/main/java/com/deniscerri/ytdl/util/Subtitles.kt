package com.deniscerri.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.VideoPreferences
import java.util.Locale

/**
 * YTindir: subtitles of a video, set in the settings (Processing > Video) and per download in the card's
 * Subtitles chip. On: the link's subtitles in the chosen languages (the phone's language unless others
 * were chosen); with YouTube's auto-translation on, a language without subtitles of its own gets YouTube's
 * automatic translation (or its automatic subtitles, in the video's own language). They are saved in the
 * video, as a separate .srt next to it, or both.
 */
object Subtitles {
    const val SAVE_EMBED = "embed"
    const val SAVE_FILE = "file"
    const val SAVE_BOTH = "both"

    const val PREF_DOWNLOAD = "download_subtitles"
    const val PREF_AUTO_TRANSLATE = "subtitles_auto_translate"
    const val PREF_LANGUAGES = "subtitle_langs"
    const val PREF_SAVE_AS = "subtitle_save_as"

    /** The phone's language as YouTube names its subtitles ("tr", "pt", "zh-Hans", "iw") */
    fun phoneLanguage(): String {
        val locale = Locale.getDefault()
        return when (locale.language) {
            "in" -> "id"
            "he" -> "iw"
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            else -> locale.language
        }
    }

    /** The languages chosen in the settings, for yt-dlp's --sub-langs */
    fun languages(prefs: SharedPreferences): String =
        prefs.getString(PREF_LANGUAGES, "")!!.trim().ifBlank { phoneLanguage() }

    fun saveAs(prefs: SharedPreferences): String =
        prefs.getString(PREF_SAVE_AS, SAVE_EMBED)!!.ifBlank { SAVE_EMBED }

    /** A new download's subtitles, from the settings */
    fun applyDefaults(videoPreferences: VideoPreferences, prefs: SharedPreferences) {
        val on = prefs.getBoolean(PREF_DOWNLOAD, false)
        set(videoPreferences, on, prefs.getBoolean(PREF_AUTO_TRANSLATE, true), saveAs(prefs))
        videoPreferences.subsLanguages = languages(prefs)
        videoPreferences.burnSubs = false
    }

    /** Subtitles on or off for a download, with YouTube's auto-translation and how they are saved */
    fun set(videoPreferences: VideoPreferences, on: Boolean, autoTranslate: Boolean, saveAs: String) {
        videoPreferences.writeSubs = on
        videoPreferences.writeAutoSubs = on && autoTranslate
        videoPreferences.embedSubs = on && saveAs != SAVE_FILE
        videoPreferences.keepSubFiles = on && saveAs != SAVE_EMBED
    }

    fun isOn(videoPreferences: VideoPreferences) = videoPreferences.writeSubs || videoPreferences.writeAutoSubs

    fun saveAs(videoPreferences: VideoPreferences): String = when {
        !videoPreferences.embedSubs -> SAVE_FILE
        videoPreferences.keepSubFiles -> SAVE_BOTH
        else -> SAVE_EMBED
    }

    /** "Türkçe, English" for "tr,en"; a yt-dlp pattern (".*-orig") stays as it is */
    fun languageNames(context: Context, languages: String): String {
        val phone = phoneLanguage()
        return languages.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { code ->
            if (code.any { c -> c in ".*^$[]()|+?\\" }) return@joinToString code
            val locale = Locale.forLanguageTag(if (code == "iw") "he" else code)
            val name = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { code }
            if (code == phone) context.getString(R.string.subs_phone_language, name) else name
        }
    }
}
