package io.github.landern.miel

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Stable GitHub releases and APKs signed by this installation's key. Call from Dispatchers.IO. */
class AppUpdates(private val context: Context) {
    data class Release(val version: String, val code: Long, val url: String, val bytes: Long)

    private val packages = context.packageManager
    private val installed
        get() =
            packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    val version: String
        get() = installed.versionName.orEmpty()

    private val directory = File(context.cacheDir, "updates")
    private val apk = File(directory, "miel-arm64-v8a.apk")

    suspend fun check(): Release? {
        val connection = connect("https://api.github.com/repos/$REPOSITORY/releases/latest")
        val body =
            try {
                if (connection.responseCode == 404) {
                    throw IOException(
                        "No public release is available. The GitHub repository may still be private.",
                    )
                }
                requireSuccess(connection)
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val started = System.nanoTime()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkDeadline(started, 30)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 2 * 1024 * 1024) {
                            throw IOException("GitHub returned too much release data.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        return parseRelease(body)?.takeIf { it.code > installed.longVersionCode }
    }

    suspend fun download(release: Release, progress: suspend (Int) -> Unit) {
        directory.mkdirs()
        val partial = File(directory, "download.part")
        apk.delete()
        partial.delete()
        val connection = connect(release.url)
        try {
            requireSuccess(connection)
            val length = connection.contentLengthLong
            if (length > MAX_APK_BYTES || (length >= 0 && length != release.bytes)) {
                throw IOException("The APK size does not match the release.")
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var previous = -1
                    val started = System.nanoTime()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkDeadline(started, 15 * 60)
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > release.bytes || total > MAX_APK_BYTES) {
                            throw IOException("The APK is larger than the release reports.")
                        }
                        output.write(buffer, 0, count)
                        val percent = (total * 100 / release.bytes).toInt()
                        if (percent != previous) {
                            progress(percent)
                            previous = percent
                        }
                    }
                    if (total != release.bytes) throw IOException("The APK download is incomplete.")
                }
            }
            validate(partial, release.code)
            if (!partial.renameTo(apk)) throw IOException("Could not keep the downloaded APK.")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    fun installerUri(): Uri {
        validate(apk)
        return FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    }

    private fun validate(file: File, expectedCode: Long? = null) {
        val archive =
            packages.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw IOException("The downloaded file is not an Android APK.")
        val current = installed
        if (
            archive.packageName != context.packageName ||
                archive.longVersionCode <= current.longVersionCode ||
                (expectedCode != null && archive.longVersionCode != expectedCode)
        ) {
            throw IOException("The APK does not match this miel update.")
        }
        val signatures = archive.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val currentSignatures = current.signingInfo?.apkContentsSigners.orEmpty().toSet()
        if (signatures.isEmpty() || signatures != currentSignatures) {
            throw IOException(
                "This installation uses a different signing key. Install a matching release to enable updates.",
            )
        }
    }

    companion object {
        internal const val REPOSITORY = "LanderN/miel"
        private const val MAX_APK_BYTES = 1024L * 1024 * 1024

        internal fun parseRelease(body: String): Release? {
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val version = json.getString("tag_name")
            val parts =
                Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})")
                    .matchEntire(version)
                    ?.groupValues
                    ?.drop(1)
                    ?.map { it.toLongOrNull() }
                    ?: throw IOException("The release has an unsupported version.")
            val major = parts[0] ?: throw IOException("The release version is too large.")
            if (major > 2100) throw IOException("The release version is too large.")
            val code = major * 1_000_000 + parts[1]!! * 1000 + parts[2]!!
            if (code !in 1..2_100_000_000L) throw IOException("The release version is too large.")
            val assets = json.getJSONArray("assets")
            val matches =
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .filter { it.optString("name") == "miel-arm64-v8a.apk" }
            val asset = matches.singleOrNull() ?: throw IOException("This release has no miel APK.")
            val url = asset.getString("browser_download_url")
            val parsed = URL(url)
            if (
                parsed.protocol != "https" ||
                    parsed.host != "github.com" ||
                    parsed.userInfo != null ||
                    (parsed.port != -1 && parsed.port != 443) ||
                    parsed.path != "/$REPOSITORY/releases/download/$version/miel-arm64-v8a.apk" ||
                    parsed.query != null ||
                    parsed.ref != null
            ) {
                throw IOException("The release APK has an unexpected address.")
            }
            val bytes = asset.getLong("size")
            if (bytes !in 1..MAX_APK_BYTES)
                throw IOException("The release APK has an unsupported size.")
            return Release(version.removePrefix("v"), code, url, bytes)
        }

        private fun connect(address: String): HttpURLConnection {
            var url = URL(address)
            repeat(6) {
                if (url.protocol != "https")
                    throw IOException("Updates require a secure connection.")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "miel-updater")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                try {
                    if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                        val location =
                            connection.getHeaderField("Location")
                                ?: throw IOException("GitHub returned an empty redirect.")
                        url = URL(url, location)
                        connection.disconnect()
                    } else return connection
                } catch (error: Exception) {
                    connection.disconnect()
                    throw error
                }
            }
            throw IOException("GitHub redirected too many times.")
        }

        private fun requireSuccess(connection: HttpURLConnection) {
            if (connection.responseCode != 200) {
                throw IOException(
                    if (connection.responseCode == 403 || connection.responseCode == 429)
                        "GitHub is limiting requests. Try again later."
                    else "GitHub could not provide this update (HTTP ${connection.responseCode}).",
                )
            }
        }

        private fun checkDeadline(started: Long, seconds: Int) {
            if (System.nanoTime() - started > seconds * 1_000_000_000L) {
                throw IOException("The update request timed out. Try again.")
            }
        }
    }
}
