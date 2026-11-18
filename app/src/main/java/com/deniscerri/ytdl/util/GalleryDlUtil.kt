package com.deniscerri.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import com.deniscerri.ytdl.core.GalleryDl
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.core.models.ExecuteException
import com.deniscerri.ytdl.core.models.ExecuteResponse
import com.deniscerri.ytdl.database.models.Format
import java.io.File

/** YTindir: what the Image tab hands to gallery-dl */
object GalleryDlUtil {
    const val INCLUDE_VIDEOS_KEY = "image_include_videos"
    private const val FORMAT_IMAGES = "gallery-dl"
    private const val FORMAT_WITH_VIDEOS = "gallery-dl+videos"

    /** Video and audio files left out of image downloads (the Video tab gives them in better quality) */
    private val mediaExtensions = listOf("mp4", "webm", "mov", "m4v", "mkv", "m3u8", "ts", "m4a", "mp3", "aac", "opus", "ogg")
    private val noMediaFilter = "extension not in (${mediaExtensions.joinToString(",") { "'$it'" }})"

    /** X's GIFs are mp4 files: they get this marker in their name and become real .gif files afterwards */
    const val GIF_MARKER = ".gif."

    private val fileNames = listOf(
        "instagram" to "{username}_{post_shortcode}_{num}.{extension}",
        "twitter" to """{"type == 'animated_gif'": "{author[name]}_{tweet_id}_{num}${GIF_MARKER}{extension}", "": "{author[name]}_{tweet_id}_{num}.{extension}"}"""
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
        fileNames.forEach { (extractor, nameFormat) ->
            args += listOf("-o", "extractor.$extractor.filename=$nameFormat")
        }

        // Reddit: its website API with the logged-in account from the cookies (without a login
        // Reddit refuses it, and gallery-dl's own app id too: SiteImages.reddit is used then)
        args += listOf("-o", "extractor.reddit.api=rest")

        // (as options under "extractor", not --filter: --filter would override the per-site ones)
        if (includesVideos(format)) {
            // Instagram's videos with their sound in one mp4, not a separate mp4 and m4a
            args += listOf("-o", "extractor.instagram.videos=merged")
        } else {
            args += listOf("-o", "extractor.file-filter=$noMediaFilter")
            // X's GIFs stay (they are mp4 files, turned into .gif after the download)
            args += listOf("-o", "extractor.twitter.file-filter=$noMediaFilter or type == 'animated_gif'")
            args += listOf("-o", "extractor.instagram.videos=false")
            // Pinterest GIFs as the original .gif instead of its mp4 version
            args += listOf("-o", "extractor.pinterest.videos=false")
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

    /** Whether the cookies file holds a logged-in account of a site (its session cookie). */
    private fun hasLogin(context: Context, domain: String, cookie: String): Boolean {
        var found = false
        FileUtil.getCookieFile(context) { path ->
            found = runCatching {
                File(path).useLines { lines ->
                    lines.any { l ->
                        val p = l.split('\t')
                        p.size >= 7 && p[0].contains(domain) && p[5] == cookie && p[6].isNotBlank()
                    }
                }
            }.getOrDefault(false)
        }
        return found
    }

    /**
     * Downloads [url]'s images into [dir]: with gallery-dl, or the app's own way in (SiteImages)
     * where gallery-dl needs a login the cookies don't have, or fails:
     * - Reddit: always the post page as the phone's browser gets it
     * - Instagram: gallery-dl only with an Instagram login; otherwise (or when it fails) the post's
     *   embed page and post page, which logged-out visitors get too
     */
    fun download(
        context: Context, url: String, format: Format, dir: File, args: List<String>, useCookies: Boolean,
        processId: String, callback: (Float, Long, String) -> Unit
    ): ExecuteResponse {
        val log: (String) -> Unit = { callback(0f, 0L, it) }
        val started = System.currentTimeMillis()
        fun done(count: Int, note: String) = ExecuteResponse(listOf(note), 0, System.currentTimeMillis() - started, "$note: $count file(s)", "")
        fun clear() = dir.listFiles()?.forEach { it.deleteRecursively() }

        val reddit = SiteImages.isReddit(url)
        val instagram = SiteImages.isInstagramPost(url)
        val loggedIn = when {
            // Reddit blocks its JSON for gallery-dl even with a login ("Your IP address is unable
            // to access the Reddit API"): its post page instead
            reddit -> false
            instagram -> useCookies && hasLogin(context, "instagram.com", "sessionid")
            else -> true
        }

        var galleryError: Throwable? = null
        if (loggedIn) {
            try {
                val response = GalleryDl.execute(context, args, processId = processId, redirectErrorStream = true, callback = callback)
                convertGifs(dir)
                if (dir.walkTopDown().any { it.isFile }) return response
                galleryError = ExecuteException(response.out.ifBlank { "gallery-dl" } + "\nNo images were found in this post")
            } catch (e: RuntimeManager.CanceledException) {
                throw e
            } catch (e: Exception) {
                galleryError = e
            }
        }
        if (!reddit && !instagram) throw galleryError!!

        val site = if (reddit) "Reddit" else "Instagram"
        log(when {
            reddit -> "[YTindir] Reddit: reading the post's page"
            loggedIn -> "[YTindir] gallery-dl couldn't read the post, trying $site's pages"
            else -> "[YTindir] No $site login in the cookies: reading the post's pages"
        })
        clear()
        return try {
            val count = if (reddit) SiteImages.reddit(context, url, dir, log)
                else SiteImages.instagram(context, url, dir, includesVideos(format), log)
            done(count, "$site pages")
        } catch (e: Exception) {
            clear()
            val first = galleryError?.message?.let { "$it\n" }.orEmpty()
            throw ExecuteException("$first[$site] ${e.message}" +
                if (loggedIn || reddit) "" else "\n$site didn't give the post without a login")
        }
    }

    /** The command as shown in the logs and the history */
    fun commandString(args: List<String>) = "gallery-dl " + args.joinToString(" ") {
        if (it.contains(' ')) "\"$it\"" else it
    }

    /**
     * Turns the marked mp4 files of GIFs into real .gif files with ffmpeg (a palette made from the
     * video keeps the colours). A file that can't be converted stays an mp4.
     */
    fun convertGifs(dir: File) {
        val ffmpeg = runCatching { RuntimeManager.ffmpegLocation }.getOrNull()
        dir.walkTopDown().filter { it.isFile && it.name.contains(GIF_MARKER) }.toList().forEach { file ->
            val base = file.name.substringBefore(GIF_MARKER)
            val gif = File(file.parentFile, "$base.gif")
            val converted = ffmpeg != null && ffmpeg.isAvailable && runCatching {
                RuntimeManager.executeImpl(listOf(
                    ffmpeg.executable.absolutePath, "-y", "-i", file.absolutePath,
                    "-vf", "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5",
                    "-loop", "0", gif.absolutePath
                ), redirectErrorStream = true)
                gif.length() > 0
            }.getOrDefault(false)
            if (converted) {
                file.delete()
            } else {
                gif.delete()
                file.renameTo(File(file.parentFile, "$base.${file.extension}"))
            }
        }
    }
}
