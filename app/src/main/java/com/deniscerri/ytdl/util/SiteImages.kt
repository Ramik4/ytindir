package com.deniscerri.ytdl.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * YTindir: the Image tab's own ways into Instagram and Reddit, for when gallery-dl can't get in:
 * - Instagram: gallery-dl reads posts only with a logged-in account ("HTTP redirect to login
 *   page" without one); the post's embed page and the post page itself give logged-out visitors
 *   its photos too
 * - Reddit: its API wants a login ("Account authentication is required") and refuses gallery-dl's
 *   app id ("401: Unauthorized"); a real browser still gets the post's JSON
 * Both sites block requests that don't come from a browser, so the pages are also opened in a
 * hidden WebView (the phone's own Chrome, with the cookies of More → Cookies' browser).
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
    private val redditShareRegex = Regex("""reddit\.com/(?:r|u|user)/[\w-]+/s/\w+""", RegexOption.IGNORE_CASE)

    fun isInstagramPost(url: String) = instagramRegex.containsMatchIn(url)
    fun isReddit(url: String) = redditRegex.containsMatchIn(url)

    private class Media(val url: String, val ext: String, val fallback: String? = null)

    // ---------------------------------------------------------------- Instagram

    /** Downloads the photos (and with [includeVideos] the videos) of an Instagram post into [dir]. */
    fun instagram(context: Context, url: String, dir: File, includeVideos: Boolean, log: (String) -> Unit): Int {
        val code = instagramRegex.find(url)?.groupValues?.get(1)
            ?: throw ExecuteException("Not an Instagram post link")
        val embedUrl = "https://www.instagram.com/p/$code/embed/captioned/"
        val attempts = listOf<Pair<String, () -> String?>>(
            "embed page" to { get(embedUrl, BROWSER_UA) },
            "post page in the browser" to { webPage(context, "https://www.instagram.com/p/$code/", 3000) },
            "embed page in the browser" to { webPage(context, embedUrl, 3000) },
        )
        for ((name, load) in attempts) {
            log("[instagram] $code: reading the $name")
            val page = runCatching { load() }.getOrNull()
            if (page.isNullOrBlank()) {
                log("[instagram] the $name couldn't be loaded")
                continue
            }
            val (user, media) = instagramMedia(page, code, includeVideos)
            if (media.isNotEmpty()) {
                return save(media, dir, user.ifBlank { "instagram" }, code, "https://www.instagram.com/", log)
            }
            log("[instagram] no photos in the $name (${describe(page)})")
        }
        throw ExecuteException("Instagram didn't show this post's photos without a login")
    }

    /** What a page that had no photos was, for the log. */
    private fun describe(page: String): String {
        val title = Regex("""<title[^>]*>([^<]*)</title>""").find(page)?.groupValues?.get(1)?.trim().orEmpty()
        val marks = listOf("contextJSON", "gql_data", "shortcode_media", "EmbeddedMedia", "web_info", "image_versions2",
            "display_url", "accounts/login", "challenge").filter { page.contains(it) }
        return "${page.length / 1024} KB, title \"${title.take(60)}\", has: ${marks.joinToString().ifEmpty { "-" }}"
    }

    private fun instagramMedia(page: String, code: String, includeVideos: Boolean): Pair<String, List<Media>> {
        // 1. the post page's data (logged-out web: xdt_api__v1__media__shortcode__web_info)
        jsonAfter(page, "\"xdt_api__v1__media__shortcode__web_info\":")?.let { info ->
            val item = info.optJSONArray("items")?.optJSONObject(0)
            if (item != null && (item.optString("code").isEmpty() || item.optString("code") == code)) {
                val user = item.optJSONObject("user")?.optString("username").orEmpty()
                val nodes = item.optJSONArray("carousel_media")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                    ?: listOf(item)
                val media = nodes.mapNotNull { node ->
                    val video = node.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
                    if (node.optInt("media_type") == 2 && !video.isNullOrBlank()) {
                        if (includeVideos) Media(video, "mp4") else null
                    } else {
                        largestCandidate(node)?.let { Media(it, extOf(it, "jpg")) }
                    }
                }
                if (media.isNotEmpty()) return user to media
            }
        }

        // 2. the embed page's data ("contextJSON" → gql_data.shortcode_media)
        val gql = contextJson(page)?.optJSONObject("gql_data")?.optJSONObject("shortcode_media")
            ?: jsonAfter(page, "\"shortcode_media\":")
        gql?.let { post ->
            val user = post.optJSONObject("owner")?.optString("username").orEmpty()
            val nodes = post.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
                ?.let { edges -> (0 until edges.length()).mapNotNull { edges.optJSONObject(it)?.optJSONObject("node") } }
                ?: listOf(post)
            val media = nodes.mapNotNull { node ->
                if (node.optBoolean("is_video")) {
                    node.optString("video_url").takeIf { includeVideos && it.isNotBlank() }?.let { Media(it, "mp4") }
                } else {
                    bestImage(node)?.let { Media(it, extOf(it, "jpg")) }
                }
            }
            if (media.isNotEmpty()) return user to media
        }

        // 3. the photos shown on the page (post photos have "-15/" in their path, profile pictures "-19/")
        val user = Regex("""class="UsernameText"[^>]*>([^<]+)<""").find(page)?.groupValues?.get(1)?.trim().orEmpty()
        val seen = mutableSetOf<String>()
        val media = Regex("""<img\b[^>]*>""").findAll(page).mapNotNull { tag ->
            val t = tag.value
            val srcset = Regex("""srcset="([^"]+)"""").find(t)?.groupValues?.get(1)?.let(::unescapeHtml)
            val best = srcset?.split(",")?.map { it.trim().split(" ") }
                ?.maxByOrNull { it.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0 }?.firstOrNull()
            val src = best ?: Regex("""src="([^"]+)"""").find(t)?.groupValues?.get(1)?.let(::unescapeHtml)
            src?.takeIf { (it.contains("cdninstagram") || it.contains("fbcdn")) && Regex("""-15/""").containsMatchIn(it) }
        }.filter { seen.add(it.substringBefore('?').substringAfterLast('/')) }
            .map { Media(it, extOf(it, "jpg")) }.toList()
        return user to media
    }

    /** The JSON object right after [key] in [page]. */
    private fun jsonAfter(page: String, key: String): JSONObject? = runCatching {
        val start = page.indexOf(key)
        if (start < 0) return null
        JSONTokener(page.substring(start + key.length)).nextValue() as? JSONObject
    }.getOrNull()

    /** The post data the embed page keeps in its "contextJSON" string. */
    private fun contextJson(page: String): JSONObject? = runCatching {
        val start = page.indexOf("\"contextJSON\":\"")
        if (start < 0) return null
        val str = JSONTokener(page.substring(start + "\"contextJSON\":".length)).nextValue() as String
        JSONObject(str)
    }.getOrNull()

    private fun largestCandidate(node: JSONObject): String? {
        val candidates = node.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: return null
        var best: JSONObject? = null
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            if (best == null || c.optInt("width") > best.optInt("width")) best = c
        }
        return best?.optString("url")?.takeIf { it.isNotBlank() }
    }

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

    /**
     * A reddit.com/r/x/s/abc share link turned into the post's own link (yt-dlp and gallery-dl get
     * "403 Blocked" for the share page). The link as it is when that doesn't work.
     */
    fun resolveRedditShareLink(context: Context, url: String, log: (String) -> Unit = {}): String {
        if (!redditShareRegex.containsMatchIn(url)) return url
        log("[reddit] resolving the share link")
        // without following redirects: Reddit's answer names the post
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        runCatching {
            noRedirects.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()).execute().use { r ->
                val location = r.header("Location").orEmpty()
                postLink(location)?.let { return it }
                postLink(r.body.string())?.let { return it }
            }
        }
        // in the browser, where the page itself goes to the post
        val href = runCatching { webPage(context, url, 1500, "location.href") }.getOrNull()
        postLink(href.orEmpty())?.let { return it }
        log("[reddit] the share link couldn't be resolved")
        return url
    }

    private fun postLink(text: String) = Regex("""https://(?:www\.|old\.)?reddit\.com/(?:r|u|user)/[\w-]+/comments/\w+[^\s"'<?]*""")
        .find(text)?.value?.replace("old.reddit.com", "www.reddit.com")
        ?: Regex("""^/(?:r|u|user)/[\w-]+/comments/\w+""").find(text)?.value?.let { "https://www.reddit.com$it" }

    /**
     * Downloads the images (and GIFs) of a Reddit post into [dir]: its JSON as the phone's browser
     * gets it (with a Reddit login of More → Cookies, if there is one), then through Reddit's app API.
     */
    fun reddit(context: Context, url: String, dir: File, log: (String) -> Unit): Int {
        val postUrl = resolveRedditShareLink(context, url, log)
        val id = Regex("""/comments/(\w+)""").find(postUrl)?.groupValues?.get(1)
            ?: Regex("""redd\.it/(\w+)""").find(postUrl)?.groupValues?.get(1)
            ?: throw ExecuteException("No Reddit post in this link: $postUrl")

        // Reddit's JSON is blocked for everything but its own apps ("blocked by network security",
        // "Your IP address is unable to access the Reddit API"), its post page is not
        val pageUrl = if (postUrl.contains("/comments/")) postUrl.substringBefore('?') else "https://www.reddit.com/comments/$id/"
        log("[reddit] $id: reading the post page in the browser")
        val errors = mutableListOf<String>()
        val html = runCatching { webPage(context, pageUrl, 2000, REDDIT_POST_SCRIPT) }.getOrNull()
        if (html != null && html.startsWith("<shreddit-post")) {
            val (user, media) = redditPageMedia(html)
            if (media.isNotEmpty()) return save(media, dir, user.ifBlank { "reddit" }, id, "https://www.reddit.com/", log)
            val type = attr(html, "post-type")
            log("[reddit] no images on the post page (post type: ${type.ifBlank { "?" }})")
            if (type == "video") throw ExecuteException("This Reddit post is a video, not images")
            errors += "post page: no images (post type: ${type.ifBlank { "?" }})"
        } else {
            val what = html?.trim()?.take(150)?.ifBlank { null } ?: "the page didn't load"
            log("[reddit] post page: $what")
            errors += "post page: $what"
        }

        log("[reddit] $id: reading the post (Reddit app API)")
        val post = try {
            redditPostFromAppApi(id)
        } catch (e: Exception) {
            errors += "Reddit app API: ${e.message}"
            throw ExecuteException(errors.joinToString("\n"))
        }
        // a crosspost: the media are on the original post
        val source = post.optJSONArray("crosspost_parent_list")?.optJSONObject(0) ?: post
        val media = redditMedia(source)
        if (media.isEmpty()) throw ExecuteException("No images were found in this Reddit post")
        val user = post.optString("author").takeIf { it.isNotBlank() && it != "[deleted]" }
            ?: post.optString("subreddit").ifBlank { "reddit" }
        return save(media, dir, user, id, "https://www.reddit.com/", log)
    }

    /** The post's own element of the page (its images; the comments are outside of it). */
    private const val REDDIT_POST_SCRIPT = "(function(){var p=document.querySelector('shreddit-post');" +
        "return p ? p.outerHTML : (document.title || '') + ' | ' + (document.body ? document.body.innerText.slice(0, 200) : '');})()"

    private fun attr(html: String, name: String) =
        Regex("""\b$name="([^"]*)"""").find(html.substringBefore('>'))?.groupValues?.get(1)?.let(::unescapeHtml).orEmpty()

    /**
     * The images of a post page's <shreddit-post>: i.redd.it files as they are, preview.redd.it
     * copies (galleries) turned into their original i.redd.it file, as gallery-dl does.
     */
    private fun redditPageMedia(html: String): Pair<String, List<Media>> {
        val user = attr(html, "author").takeIf { it != "[deleted]" }.orEmpty()
        val text = unescapeHtml(html)
        val seen = mutableSetOf<String>()
        val media = Regex("""https://(i|preview)\.redd\.it/([^"'\s,?/]+)\.(jpe?g|png|gif|webp)(\?[^"'\s,]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text).mapNotNull { m ->
                val (host, name, extRaw) = m.destructured
                val ext = extRaw.lowercase().replace("jpeg", "jpg")
                // "title-words-v0-abc123" → abc123
                val mediaId = name.substringAfterLast("-v0-")
                if (!seen.add(mediaId)) return@mapNotNull null
                if (host.equals("i", true)) Media(m.value.substringBefore('?'), ext)
                else Media("https://i.redd.it/$mediaId.$ext", ext, fallback = m.value)
            }.toList()
        return user to media
    }

    private fun redditPost(json: String): JSONObject {
        val array = runCatching { JSONArray(json) }.getOrNull()
            ?: throw ExecuteException("not the post's data: ${json.trim().take(150)}")
        return array.getJSONObject(0).getJSONObject("data").getJSONArray("children")
            .getJSONObject(0).getJSONObject("data")
    }

    private fun redditPostFromAppApi(id: String): JSONObject {
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
            val body = r.body.string()
            r.header("x-reddit-loid")?.let { headers["x-reddit-loid"] = it }
            r.header("x-reddit-session")?.let { headers["x-reddit-session"] = it }
            runCatching { JSONObject(body).optString("access_token") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: throw ExecuteException("no app token (HTTP ${r.code})")
        }
        headers["Authorization"] = "Bearer $token"
        val postRequest = Request.Builder()
            .url("https://oauth.reddit.com/comments/$id/.json?raw_json=1&limit=1")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return client.newCall(postRequest).execute().use { r ->
            if (!r.isSuccessful) throw ExecuteException("HTTP ${r.code}")
            redditPost(r.body.string())
        }
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

    // ---------------------------------------------------------------- shared

    private fun get(url: String, ua: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept-Language", "en-US,en;q=0.9").build()).execute().use { r ->
            if (r.isSuccessful) r.body.string() else null
        }
    }.getOrNull()

    /**
     * Opens [url] in a hidden WebView and returns what [script] gives once the page has finished
     * loading and [settleMs] more have passed (for pages that build themselves with JavaScript).
     * Null when it can't be done (no WebView, the main thread, a timeout).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webPage(
        context: Context, url: String, settleMs: Long,
        script: String = "document.documentElement.outerHTML", timeoutMs: Long = 30_000
    ): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var result: String? = null
        var view: WebView? = null
        val grab = Runnable {
            val v = view
            if (v == null) latch.countDown()
            else v.evaluateJavascript(script) { value ->
                result = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
                latch.countDown()
            }
        }
        handler.post {
            try {
                val wv = WebView(context.applicationContext)
                view = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.blockNetworkImage = true
                // as the phone's Chrome, not an app's WebView
                wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        // (again after each redirect: the last page counts)
                        handler.removeCallbacks(grab)
                        handler.postDelayed(grab, settleMs)
                    }
                }
                wv.loadUrl(url)
            } catch (e: Throwable) {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        handler.post {
            handler.removeCallbacks(grab)
            view?.stopLoading()
            view?.destroy()
            view = null
        }
        return result
    }

    private fun save(media: List<Media>, dir: File, user: String, postId: String, referer: String, log: (String) -> Unit): Int {
        dir.mkdirs()
        val safeUser = user.replace(Regex("""[^\w.-]"""), "_")
        media.forEachIndexed { i, m ->
            val file = File(dir, "${safeUser}_${postId}_${i + 1}.${m.ext}")
            log("[download] ${file.name}")
            val urls = listOfNotNull(m.url, m.fallback)
            for ((n, u) in urls.withIndex()) {
                val ok = client.newCall(Request.Builder().url(u).header("User-Agent", BROWSER_UA).header("Referer", referer).build())
                    .execute().use { r ->
                        if (r.isSuccessful) file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                        r.isSuccessful.also { if (!it && n == urls.lastIndex) throw ExecuteException("HTTP Error ${r.code} downloading $u") }
                    }
                if (ok) break
            }
        }
        return media.size
    }

    private fun extOf(url: String, fallback: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: fallback

    private fun unescapeHtml(s: String) = s.replace("&amp;", "&").replace("&#x2F;", "/").replace("&quot;", "\"")
}
