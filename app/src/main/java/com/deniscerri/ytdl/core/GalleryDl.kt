package com.deniscerri.ytdl.core

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.models.ExecuteException
import com.deniscerri.ytdl.core.models.ExecuteResponse
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * YTindir: gallery-dl (https://github.com/mikf/gallery-dl) for images and galleries.
 *
 * gallery-dl is pure Python and only needs `requests`, which the bundled Python already has, so it
 * runs from a zip on the embedded Python, the same way yt-dlp does. A copy ships in res/raw, so it
 * works offline from the first launch, and it updates itself from PyPI (stable) or GitHub (master).
 * The installed zip always has the `gallery_dl` package at its root.
 */
object GalleryDl {
    private const val TAG = "GalleryDl"

    const val DIR_NAME = "gallery-dl"
    const val FILE_NAME = "gallery_dl.zip"

    /** The version in res/raw/gallery_dl.zip. Bump it together with that file */
    const val BUNDLED_VERSION = "1.32.20"

    const val SOURCE_KEY = "gallery_dl_source"
    const val AUTO_UPDATE_KEY = "auto_update_gallery_dl"
    const val LAST_CHECK_KEY = "gallery_dl_last_update_check"
    private const val VERSION_KEY = "galleryDlVersion"
    private const val COMMIT_KEY = "galleryDlCommit"

    const val CHANNEL_STABLE = "stable"
    const val CHANNEL_MASTER = "master"
    val channels = listOf(CHANNEL_STABLE, CHANNEL_MASTER)

    private const val PYPI_URL = "https://pypi.org/pypi/gallery-dl/json"
    private const val MASTER_COMMIT_URL = "https://api.github.com/repos/mikf/gallery-dl/commits/master"
    private const val MASTER_ZIP_URL = "https://github.com/mikf/gallery-dl/archive/%s.zip"

    /**
     * Puts the gallery-dl zip and yt-dlp (which gallery-dl uses for videos) on sys.path, takes both
     * paths out of argv and hands the rest to gallery-dl
     */
    private const val BOOTSTRAP =
        "import sys;p=sys.argv[1:3];del sys.argv[1:3];sys.path[0:0]=[x for x in p if x];" +
        "from gallery_dl import main;sys.exit(main())"

    enum class UpdateStatus { DONE, ALREADY_UP_TO_DATE }

    private val lock = Any()

    fun getDir(context: Context) = File(File(context.noBackupFilesDir, RuntimeManager.BASENAME), DIR_NAME)
    fun getFile(context: Context) = File(getDir(context), FILE_NAME)

    /**
     * Installs the bundled copy when nothing is installed yet, or when an app update brought a newer
     * stable version than the installed stable one. A master install is left alone.
     */
    fun init(context: Context) {
        synchronized(lock) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val file = getFile(context)
            val installed = prefs.getString(VERSION_KEY, null)
            val isMaster = !prefs.getString(COMMIT_KEY, null).isNullOrBlank()

            val needsCopy = !file.exists() || installed.isNullOrBlank() ||
                (!isMaster && compareVersions(BUNDLED_VERSION, installed) > 0)
            if (!needsCopy) return

            try {
                val tmp = File(context.cacheDir, "$FILE_NAME.tmp")
                context.resources.openRawResource(R.raw.gallery_dl).use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                }
                install(context, tmp)
                prefs.edit {
                    putString(VERSION_KEY, BUNDLED_VERSION)
                    remove(COMMIT_KEY)
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to install the bundled gallery-dl", e)
            }
        }
    }

    fun isAvailable(context: Context) = getFile(context).exists()

    /** "1.32.20", or "1.32.21-dev (abc1234)" on the master channel */
    fun version(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val version = prefs.getString(VERSION_KEY, null).orEmpty().ifBlank { BUNDLED_VERSION }
        val commit = prefs.getString(COMMIT_KEY, null).orEmpty()
        return if (commit.isBlank()) version else "$version (${commit.take(7)})"
    }

    fun currentChannel(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(SOURCE_KEY, CHANNEL_STABLE)!!
            .takeIf { channels.contains(it) } ?: CHANNEL_STABLE

    fun buildCommand(context: Context, args: List<String>): List<String> {
        RuntimeManager.assertInit()
        if (!isAvailable(context)) init(context)
        if (!isAvailable(context)) throw ExecuteException("gallery-dl is not installed")
        return listOf(
            RuntimeManager.pythonLocation.executable.absolutePath,
            "-c", BOOTSTRAP,
            getFile(context).absolutePath,
            RuntimeManager.ytdlpPath?.absolutePath.orEmpty()
        ) + args
    }

    fun execute(
        context: Context,
        args: List<String>,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ): ExecuteResponse {
        return RuntimeManager.executeImpl(buildCommand(context, args), processId, redirectErrorStream, callback = callback)
    }

    /** Updates to the newest version of [channel] and switches to it when it differs from the installed one */
    @Throws(IOException::class, ExecuteException::class)
    fun update(context: Context, channel: String = currentChannel(context)): UpdateStatus {
        synchronized(lock) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val status = when (channel) {
                CHANNEL_MASTER -> updateFromMaster(context)
                else -> updateFromPyPI(context)
            }
            prefs.edit { putLong(LAST_CHECK_KEY, System.currentTimeMillis()) }
            return status
        }
    }

    private fun updateFromPyPI(context: Context): UpdateStatus {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val json = JSONObject(fetch(PYPI_URL))
        val latest = json.getJSONObject("info").getString("version")

        val onStable = prefs.getString(COMMIT_KEY, null).isNullOrBlank()
        if (onStable && latest == prefs.getString(VERSION_KEY, null) && isAvailable(context)) {
            return UpdateStatus.ALREADY_UP_TO_DATE
        }

        val urls = json.getJSONArray("urls")
        var wheelUrl: String? = null
        var sha256: String? = null
        for (i in 0 until urls.length()) {
            val item = urls.getJSONObject(i)
            if (item.optString("packagetype") == "bdist_wheel" && item.optString("filename").endsWith("py3-none-any.whl")) {
                wheelUrl = item.getString("url")
                sha256 = item.optJSONObject("digests")?.optString("sha256")
                break
            }
        }
        if (wheelUrl == null) throw ExecuteException("Unable to get the gallery-dl download url")

        val tmp = download(context, wheelUrl)
        try {
            if (!sha256.isNullOrBlank() && sha256File(tmp) != sha256.lowercase()) {
                throw ExecuteException("gallery-dl download is corrupt (checksum mismatch)")
            }
            install(context, tmp)
        } finally {
            tmp.delete()
        }

        prefs.edit {
            putString(VERSION_KEY, latest)
            remove(COMMIT_KEY)
        }
        return UpdateStatus.DONE
    }

    private fun updateFromMaster(context: Context): UpdateStatus {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val sha = JSONObject(fetch(MASTER_COMMIT_URL)).getString("sha")
        if (sha == prefs.getString(COMMIT_KEY, null) && isAvailable(context)) {
            return UpdateStatus.ALREADY_UP_TO_DATE
        }

        val archive = download(context, MASTER_ZIP_URL.format(sha))
        val repacked = File(context.cacheDir, "$FILE_NAME.master")
        try {
            repackArchive(archive, repacked)
            install(context, repacked)
        } finally {
            archive.delete()
            repacked.delete()
        }

        val version = readVersion(getFile(context)) ?: "master"
        prefs.edit {
            putString(VERSION_KEY, version)
            putString(COMMIT_KEY, sha)
        }
        return UpdateStatus.DONE
    }

    /** GitHub's archive keeps everything under "gallery-dl-<sha>/": keep only the package, at the root */
    private fun repackArchive(archive: File, out: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zin ->
            ZipOutputStream(FileOutputStream(out).buffered()).use { zout ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfter('/', "")
                    if (!entry.isDirectory && name.startsWith("gallery_dl/") && !name.contains("..")) {
                        zout.putNextEntry(ZipEntry(name))
                        zin.copyTo(zout)
                        zout.closeEntry()
                    }
                    entry = zin.nextEntry
                }
            }
        }
    }

    /** Checks the zip really holds gallery-dl, then swaps it in atomically so a running download is not disturbed */
    private fun install(context: Context, zip: File) {
        val valid = runCatching {
            ZipFile(zip).use { it.getEntry("gallery_dl/__init__.py") != null && it.getEntry("gallery_dl/version.py") != null }
        }.getOrDefault(false)
        if (!valid) {
            zip.delete()
            throw ExecuteException("The gallery-dl download is not a valid package")
        }

        val dir = getDir(context).apply { mkdirs() }
        val staged = File(dir, "$FILE_NAME.new")
        zip.copyTo(staged, overwrite = true)
        if (!staged.renameTo(getFile(context))) {
            staged.delete()
            throw ExecuteException("Failed to install gallery-dl")
        }
        zip.delete()
    }

    private fun readVersion(zip: File): String? = runCatching {
        ZipFile(zip).use { z ->
            val text = z.getInputStream(z.getEntry("gallery_dl/version.py")).bufferedReader().readText()
            Regex("""__version__\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} from $url")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(context: Context, url: String): File {
        val file = File.createTempFile("gallery-dl", null, context.cacheDir)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} from $url")
            }
            connection.inputStream.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        return file
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Compares dotted versions like 1.32.20 and 1.33.0 (anything after a "-" is ignored) */
    private fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
