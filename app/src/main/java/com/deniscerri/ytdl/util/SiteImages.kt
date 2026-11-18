package com.deniscerri.ytdl.util

import android.util.Base64
import com.deniscerri.ytdl.core.models.ExecuteException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * YTindir: the Image tab's own ways into Instagram and Reddit, for when gallery-dl can't get in:
 * - Instagram: gallery-dl reads posts only with a logged-in account ("HTTP redirect to login
 *   page" without one); the post's embed page gives its photos to logged-out visitors too.
 * - Reddit: its website API wants a login ("Account authentication is required") and gallery-dl's
 *   app id is refused ("401: Unauthorized"); Reddit's Android app gets a token without a login.
 * Files are named like the other social media downloads: user_postid_1.jpg, user_postid_2.jpg…
 */
object SiteImages {
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val instagramRegex = Regex("""instagram\.com/(?:[\w.]+/)?(?:p|reel|reels|tv)/([\w-]+)""", RegexOption.IGNORE_CASE)
    private val redditRegex = Regex("""(?:^|[/.])(?:reddit\.com|redd\.it)(?:/|$)""", RegexOption.IGNORE_CASE)

    fun isInstagramPost(url: String) = instagramRegex.containsMatchIn(url)
    fun isReddit(url: String) = redditRegex.containsMatchIn(url)

    private class Media(val url: String, val ext: String)

    // ---------------------------------------------------------------- Instagram

    /** Downloads the photos (and with [includeVideos] the videos) of an Instagram post into [dir]. */
    fun instagram(url: String, dir: File, includeVideos: Boolean, log: (String) -> Unit): Int {
        val code = instagramRegex.find(url)?.groupValues?.get(1)
            ?: throw ExecuteException("Not an Instagram post link")
        log("[instagram-embed] $code: reading the post's embed page")
        val page = get("https://www.instagram.com/p/$code/embed/captioned/", BROWSER_UA)
            ?: throw ExecuteException("Instagram's embed page couldn't be loaded")

        var user = ""
        val media = mutableListOf<Media>()

        contextJson(page)?.optJSONObject("gql_data")?.optJSONObject("shortcode_media")?.let { post ->
            user = post.optJSONObject("owner")?.optString("username").orEmpty()
            val nodes = post.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
                ?.let { edges -> (0 until edges.length()).mapNotNull { edges.optJSONObject(it)?.optJSONObject("node") } }
                ?: listOf(post)
            nodes.forEach { node ->
                if (node.optBoolean("is_video")) {
                    if (includeVideos) node.optString("video_url").takeIf { it.isNotBlank() }?.let { media += Media(it, "mp4") }
                } else {
                    bestImage(node)?.let { media += Media(it, extOf(it, "jpg")) }
                }
            }
        }

        // embed pages without the post data: the photo shown on the page
        if (media.isEmpty()) {
            val img = Regex("""class="EmbeddedMediaImage"[^>]*?src="([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: Regex("""<img[^>]+class="EmbeddedMediaImage"[^>]*>""").find(page)?.value
                    ?.let { tag -> Regex("""src="([^"]+)"""").find(tag)?.groupValues?.get(1) }
            img?.let { media += Media(unescapeHtml(it), extOf(unescapeHtml(it), "jpg")) }
        }
        if (user.isBlank()) {
            user = Regex("""class="UsernameText"[^>]*>([^<]+)<""").find(page)?.groupValues?.get(1)?.trim().orEmpty()
        }
        if (media.isEmpty()) throw ExecuteException("Instagram's embed page has no photos for this post (it may be private or need a login)")

        return save(media, dir, user.ifBlank { "instagram" }, code, "https://www.instagram.com/", log)
    }

    /** The post data the embed page keeps in its "contextJSON" string. */
    private fun contextJson(page: String): JSONObject? = runCatching {
        val start = page.indexOf("\"contextJSON\":\"")
        if (start < 0) return null
        val str = JSONTokener(page.substring(start + "\"contextJSON\":".length)).nextValue() as String
        JSONObject(str)
    }.getOrNull()

    private fun bestImage(node: JSONObject): String? {
        val resources = node.optJSONArray("display_resources")
        if (resources != null && resources.length() > 0) {
            var best: JSONObject? = null
            for (i in 0 until resources.length()) {
                val r = resources.optJSONObject(i) ?: continue
                if (best == null || r.optInt("config_width") > best.optInt("config_width")) best = r
            }
            best?.optString("src")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return node.optString("display_url").takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- Reddit

    private const val REDDIT_ANDROID_CLIENT_ID = "ohXpoqrZYub1kg"
    private const val REDDIT_ANDROID_UA = "Reddit/Version 2025.43.0/Build 2543005/Android 14"

    /** Downloads the images (and GIFs) of a Reddit post into [dir] through Reddit's Android app API. */
    fun reddit(url: String, dir: File, log: (String) -> Unit): Int {
        val postUrl = resolveRedditShareLink(url, log)
        val id = Regex("""/comments/(\w+)""").find(postUrl)?.groupValues?.get(1)
            ?: Regex("""redd\.it/(\w+)""").find(postUrl)?.groupValues?.get(1)
            ?: throw ExecuteException("No Reddit post in this link: $postUrl")

        log("[reddit-app] $id: requesting a token as the Reddit app")
        val deviceId = UUID.randomUUID().toString()
        val headers = mutableMapOf(
            "User-Agent" to REDDIT_ANDROID_UA,
            "Client-Vendor-Id" to deviceId,
            "X-Reddit-Device-Id" to deviceId,
        )
        val auth = "Basic " + Base64.encodeToString("$REDDIT_ANDROID_CLIENT_ID:".toByteArray(), Base64.NO_WRAP)
        val tokenRequest = Request.Builder()
            .url("https://www.reddit.com/auth/v2/oauth/access-token/loid")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Authorization", auth)
            .post("""{"scopes":["*","email","pii"]}""".toRequestBody("application/json".toMediaType()))
            .build()
        val token = client.newCall(tokenRequest).execute().use { r ->
            val body = r.body?.string().orEmpty()
            r.header("x-reddit-loid")?.let { headers["x-reddit-loid"] = it }
            r.header("x-reddit-session")?.let { headers["x-reddit-session"] = it }
            runCatching { JSONObject(body).optString("access_token") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: throw ExecuteException("Reddit refused the app token (HTTP ${r.code}): ${body.take(200)}")
        }
        headers["Authorization"] = "Bearer $token"

        log("[reddit-app] $id: reading the post")
        val postRequest = Request.Builder()
            .url("https://oauth.reddit.com/comments/$id/.json?raw_json=1&limit=1")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val post = client.newCall(postRequest).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ExecuteException("Reddit didn't return the post (HTTP ${r.code}): ${body.take(200)}")
            JSONArray(body).getJSONObject(0).getJSONObject("data").getJSONArray("children")
                .getJSONObject(0).getJSONObject("data")
        }
        // a crosspost: the media are on the original post
        val source = post.optJSONArray("crosspost_parent_list")?.optJSONObject(0) ?: post
        val media = redditMedia(source)
        if (media.isEmpty()) throw ExecuteException("No images were found in this Reddit post")
        val user = post.optString("author").takeIf { it.isNotBlank() && it != "[deleted]" }
            ?: post.optString("subreddit").ifBlank { "reddit" }
        return save(media, dir, user, id, "https://www.reddit.com/", log)
    }

    private fun redditMedia(post: JSONObject): List<Media> {
        val media = mutableListOf<Media>()
        val metadata = post.optJSONObject("media_metadata")
        val items = post.optJSONObject("gallery_data")?.optJSONArray("items")
        if (metadata != null && items != null) {
            for (i in 0 until items.length()) {
                val mediaId = items.optJSONObject(i)?.optString("media_id") ?: continue
                val meta = metadata.optJSONObject(mediaId) ?: continue
                if (meta.optString("status") != "valid") continue
                val s = meta.optJSONObject("s")
                when (meta.optString("e")) {
                    "AnimatedImage" -> s?.optString("gif")?.takeIf { it.isNotBlank() }?.let { media += Media(it, "gif") }
                        ?: s?.optString("mp4")?.takeIf { it.isNotBlank() }?.let { media += Media(it, "mp4") }
                    "Image" -> {
                        // the original file on i.redd.it, as gallery-dl does
                        val ext = meta.optString("m").substringAfter('/', "jpg").replace("jpeg", "jpg")
                        media += Media("https://i.redd.it/$mediaId.$ext", ext)
                    }
                }
            }
            if (media.isNotEmpty()) return media
        }

        val link = post.optString("url_overridden_by_dest").ifBlank { post.optString("url") }
        val linkExt = extOf(link, "")
        if (linkExt in listOf("jpg", "jpeg", "png", "gif", "webp") &&
            Regex("""^https?://(i\.redd\.it|i\.imgur\.com|preview\.redd\.it)/""").containsMatchIn(link)) {
            return listOf(Media(link, linkExt.replace("jpeg", "jpg")))
        }

        // a link post with a picture: Reddit's copy of it
        if (post.optString("post_hint") == "image") {
            val image = post.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
            image?.optJSONObject("variants")?.optJSONObject("gif")?.optJSONObject("source")?.optString("url")
                ?.takeIf { it.isNotBlank() }?.let { return listOf(Media(it, "gif")) }
            image?.optJSONObject("source")?.optString("url")?.takeIf { it.isNotBlank() }
                ?.let { return listOf(Media(it, extOf(it, "jpg"))) }
        }
        return media
    }

    /** reddit.com/r/x/s/abc share links point to the post through a redirect. */
    private fun resolveRedditShareLink(url: String, log: (String) -> Unit): String {
        if (!Regex("""/s/\w+""").containsMatchIn(url)) return url
        log("[reddit-app] resolving the share link")
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = url
        repeat(5) {
            val location = runCatching {
                noRedirects.newCall(Request.Builder().url(current).header("User-Agent", BROWSER_UA).build()).execute()
                    .use { r ->
                        if (r.isRedirect) r.header("Location")
                        // a page instead of the redirect: the post's link in it
                        else Regex("""https://www\.reddit\.com/r/\w+/comments/\w+""").find(r.body?.string().orEmpty())?.value
                    }
            }.getOrNull() ?: return current
            current = if (location.startsWith("/")) "https://www.reddit.com$location" else location
            if (current.contains("/comments/")) return current
        }
        return current
    }

    // ---------------------------------------------------------------- shared

    private fun get(url: String, ua: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun save(media: List<Media>, dir: File, user: String, postId: String, referer: String, log: (String) -> Unit): Int {
        dir.mkdirs()
        val safeUser = user.replace(Regex("""[^\w.-]"""), "_")
        media.forEachIndexed { i, m ->
            val file = File(dir, "${safeUser}_${postId}_${i + 1}.${m.ext}")
            log("[download] ${file.name}")
            client.newCall(Request.Builder().url(m.url).header("User-Agent", BROWSER_UA).header("Referer", referer).build())
                .execute().use { r ->
                    if (!r.isSuccessful) throw ExecuteException("HTTP Error ${r.code} downloading ${m.url}")
                    file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
        }
        return media.size
    }

    private fun extOf(url: String, fallback: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: fallback

    private fun unescapeHtml(s: String) = s.replace("&amp;", "&").replace("&#x2F;", "/").replace("&quot;", "\"")
}
