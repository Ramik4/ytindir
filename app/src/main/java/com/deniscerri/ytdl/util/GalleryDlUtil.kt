package com.deniscerri.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import com.deniscerri.ytdl.database.models.Format

/** YTindir: what the Image tab hands to gallery-dl */
object GalleryDlUtil {
    const val INCLUDE_VIDEOS_KEY = "image_include_videos"
    private const val FORMAT_IMAGES = "gallery-dl"
    private const val FORMAT_WITH_VIDEOS = "gallery-dl+videos"

    /** Video files left out of image downloads (the Video tab gives them in better quality) */
    private val videoExtensions = listOf("mp4", "webm", "mov", "m4v", "mkv", "m3u8", "ts")

    private val fileNames = listOf(
        "instagram" to "{username}_{post_shortcode}_{num}.{extension}",
        "twitter" to "{author[name]}_{tweet_id}_{num}.{extension}"
    )

    fun imageFormat(includeVideos: Boolean) = Format(
        format_id = if (includeVideos) FORMAT_WITH_VIDEOS else FORMAT_IMAGES,
        container = "",
        format_note = "gallery-dl"
    )

    fun defaultFormat(preferences: SharedPreferences) = imageFormat(preferences.getBoolean(INCLUDE_VIDEOS_KEY, false))

    fun includesVideos(format: Format) = format.format_id == FORMAT_WITH_VIDEOS

    /**
     * gallery-dl's arguments for [url], downloading into [outDir] without sub folders. Cookies, proxy,
     * IPv4, the certificate check and the file date follow the same settings as yt-dlp.
     */
    fun buildArgs(context: Context, preferences: SharedPreferences, url: String, format: Format, outDir: String, useCookies: Boolean): List<String> {
        val args = mutableListOf("-D", outDir)

        // the same "username_postid" names as the app's other social media downloads (name_1, name_2…
        // for a carousel) instead of gallery-dl's bare media ids
        fileNames.forEach { (extractor, format) ->
            args += listOf("-o", "extractor.$extractor.filename=$format")
        }

        if (!includesVideos(format)) {
            args += listOf("--filter", "extension not in (${videoExtensions.joinToString(",") { "'$it'" }})")
        }

        if (useCookies) {
            FileUtil.getCookieFile(context) { args += listOf("--cookies", it) }
            val header = preferences.getString("useragent_header", "").orEmpty()
            if (preferences.getBoolean("use_header", false) && header.isNotBlank()) {
                args += listOf("--user-agent", header)
            }
        }

        val proxy = preferences.getString("proxy", "").orEmpty()
        if (proxy.isNotBlank()) args += listOf("--proxy", proxy)
        if (preferences.getBoolean("force_ipv4", false)) args += "-4"
        if (preferences.getBoolean("no_check_certificates", false)) args += "--no-check-certificate"
        if (!preferences.getBoolean("mtime", false)) args += "--no-mtime"

        args += url
        return args
    }

    /** The command as shown in the logs and the history */
    fun commandString(args: List<String>) = "gallery-dl " + args.joinToString(" ") {
        if (it.contains(' ')) "\"$it\"" else it
    }
}
