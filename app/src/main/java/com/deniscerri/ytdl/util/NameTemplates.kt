package com.deniscerri.ytdl.util

import android.content.Context
import com.deniscerri.ytdl.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * YTindir: file name templates people can read: ready-made ones with names ("Channel - Title") and an
 * example of the name a template gives ("Channel - Video title.mp4"), worked out here without yt-dlp so
 * it shows right away, also in the settings where there is no link to try it on.
 */
object NameTemplates {

    const val DEFAULT = "%(uploader).30B - %(title).170B"
    private const val DATE = "%(upload_date>%Y-%m-%d)s"

    /** The ready-made templates: their name and the template */
    fun presets(context: Context): List<Pair<String, String>> {
        val channel = context.getString(R.string.name_part_channel)
        val title = context.getString(R.string.title)
        val date = context.getString(R.string.name_part_date)
        return listOf(
            "$channel - $title" to DEFAULT,
            title to "%(title).200B",
            "$title [ID]" to "%(title).170B [%(id)s]",
            "$date - $title" to "$DATE - %(title).170B",
            "$channel - $date - $title" to "%(uploader).30B - $DATE - %(title).150B",
        )
    }

    // %(field...)spec: the fields (with a,b alternatives, |default, &replacement, >date format) and the
    // conversion (s, d, B, 03d...)
    private val field = Regex("""%\(([^)]*)\)[-#0+ ]*\d*(?:\.\d+)?[a-zA-Z]""")

    /** "Channel - Video title.mp4" for "%(uploader).30B - %(title).170B" */
    fun example(context: Context, template: String, extension: String = "mp4"): String {
        val today = Date()
        val samples = mapOf(
            "uploader" to context.getString(R.string.name_part_channel),
            "channel" to context.getString(R.string.name_part_channel),
            "creator" to context.getString(R.string.name_part_channel),
            "artist" to context.getString(R.string.name_part_channel),
            "uploader_id" to "@channel",
            "title" to context.getString(R.string.name_sample_title),
            "fulltitle" to context.getString(R.string.name_sample_title),
            "track" to context.getString(R.string.name_sample_title),
            "id" to "dQw4w9WgXcQ",
            "display_id" to "dQw4w9WgXcQ",
            "playlist" to context.getString(R.string.playlist),
            "playlist_title" to context.getString(R.string.playlist),
            "playlist_index" to "1",
            "playlist_autonumber" to "1",
            "autonumber" to "1",
            "rownumber" to "1",
            "height" to "1080",
            "resolution" to "1920x1080",
            "extractor" to "youtube",
            "extractor_key" to "Youtube",
            "webpage_url_domain" to "youtube.com",
            "duration_string" to "3:32",
            "ext" to extension,
        )
        val name = field.replace(template) { m ->
            val inside = m.groupValues[1]
            val default = inside.substringAfter('|', "").takeIf { inside.contains('|') }
            val names = inside.substringBefore('|').substringBefore('&').substringBefore('>')
            val dateFormat = inside.substringBefore('|').substringAfter('>', "").takeIf { inside.contains('>') }
            val key = names.split(',').map { it.trim() }.firstOrNull { it in samples || it.endsWith("date") }
            when {
                key == null -> default ?: "NA"
                key.endsWith("date") -> formatDate(today, dateFormat ?: "%Y%m%d")
                else -> samples[key]!!
            }
        }
        return if (name.endsWith(".$extension")) name else "$name.$extension"
    }

    /** yt-dlp's strftime date format (%Y-%m-%d) */
    private fun formatDate(date: Date, format: String): String {
        val pattern = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c == '%' && i + 1 < format.length) {
                pattern.append(when (format[i + 1]) {
                    'Y' -> "yyyy"
                    'y' -> "yy"
                    'm' -> "MM"
                    'd' -> "dd"
                    'H' -> "HH"
                    'M' -> "mm"
                    'S' -> "ss"
                    'B' -> "MMMM"
                    'b' -> "MMM"
                    else -> ""
                })
                i += 2
            } else {
                if (c.isLetter()) pattern.append("'").append(c).append("'") else pattern.append(c)
                i++
            }
        }
        return runCatching { SimpleDateFormat(pattern.toString(), Locale.getDefault()).format(date) }.getOrDefault("")
    }
}
