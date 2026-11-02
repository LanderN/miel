package io.github.landern.miel

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * A Vibepollo host's apps, listed through its web API with a read-only API token and streamed
 * through Artemis or Moonlight. The host's self-signed certificate is pinned on first contact.
 */
class Moonlight(context: Context) {
    private val preferences = context.getSharedPreferences("moonlight", Context.MODE_PRIVATE)
    private val covers = File(context.filesDir, "moonlight-covers")

    /** The web API's base URL, or an empty string. */
    val address: String
        get() = preferences.getString(ADDRESS, "").orEmpty()

    val configured: Boolean
        get() = address.isNotEmpty()

    /**
     * Stores `address` (a host, host:port or URL) and `token`, or keeps the stored token when
     * `token` is blank, and forgets the pinned certificate.
     */
    fun save(address: String, token: String) {
        val trimmed = address.trim().trimEnd('/')
        val url = if ("://" in trimmed) trimmed else "https://$trimmed"
        val base = if (Uri.parse(url).port == -1) "$url:$PORT" else url
        val kept = token.trim().ifEmpty { preferences.getString(TOKEN, "").orEmpty() }
        preferences.edit().clear().putString(ADDRESS, base).putString(TOKEN, kept).apply()
    }

    fun forget() {
        preferences.edit().clear().apply()
        covers.deleteRecursively()
    }

    /** The host's apps as PC games, with their covers saved to files. Blocking. */
    fun apps(): List<Game> {
        val response = JSONObject(String(get("/api/apps", LIST_LIMIT), Charsets.UTF_8))
        val host = response.getString("host_uuid")
        val list = response.getJSONArray("apps")
        covers.mkdirs()
        val kept = HashSet<String>()
        val games =
            (0 until list.length()).mapNotNull { index ->
                val app = list.getJSONObject(index)
                val uuid =
                    app.optString("uuid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = app.optString("name")
                val cover = cover(uuid, app.optString("image-version"))
                if (cover != null) kept.add(cover.name)
                val steam = app.optString("steam-id").takeIf { it.toUIntOrNull() != null }
                Game(
                    id = "moonlight-${uuid.lowercase()}",
                    title = name,
                    platform = DroidDeck.PLATFORM,
                    region = "",
                    system = "",
                    romUri = "$PREFIX$host/$uuid/${app.optString("id")}/${steam.orEmpty()}",
                    romFilename = name,
                    cover = cover?.let { Uri.fromFile(it).toString() }.orEmpty(),
                    screenshot =
                        steam
                            ?.let {
                                "https://cdn.akamai.steamstatic.com/steam/apps/$it/library_hero.jpg"
                            }
                            .orEmpty(),
                )
            }
        covers.listFiles()?.filter { it.name !in kept }?.forEach(File::delete)
        return games
    }

    /** `uuid`'s cover at `version`, downloaded once, or null when the app has none. */
    private fun cover(uuid: String, version: String): File? {
        val file = File(covers, "$uuid-${version.filter(Char::isLetterOrDigit)}.png")
        if (file.isFile) return file
        val bytes =
            try {
                get("/api/apps/$uuid/cover?v=${Uri.encode(version)}", COVER_LIMIT)
            } catch (error: FileNotFoundException) {
                return null
            }
        file.writeBytes(bytes)
        return file
    }

    private fun get(path: String, limit: Int): ByteArray {
        val connection = URL(address + path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = tls.socketFactory
        connection.setHostnameVerifier { _, _ -> true }
        connection.connectTimeout = 4000
        connection.readTimeout = 8000
        connection.setRequestProperty("Authorization", "Bearer ${preferences.getString(TOKEN, "")}")
        try {
            when (val code = connection.responseCode) {
                200 -> {}
                404 -> throw FileNotFoundException(path)
                401,
                403 -> throw IOException("Vibepollo refused the API token (HTTP $code)")
                else -> throw IOException("Vibepollo answered HTTP $code")
            }
            return connection.inputStream.use { readLimited(it, limit) }
        } finally {
            connection.disconnect()
        }
    }

    /** Trusts the certificate first seen after `save`, and only that one. */
    private val tls =
        SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf(
                    object : X509TrustManager {
                        override fun checkServerTrusted(
                            chain: Array<X509Certificate>,
                            authType: String,
                        ) {
                            val fingerprint =
                                MessageDigest.getInstance("SHA-256")
                                    .digest(chain[0].encoded)
                                    .joinToString("") { "%02x".format(it) }
                            val pinned = preferences.getString(CERTIFICATE, "").orEmpty()
                            if (pinned.isEmpty())
                                preferences.edit().putString(CERTIFICATE, fingerprint).apply()
                            else if (pinned != fingerprint)
                                throw CertificateException(
                                    "Vibepollo's certificate changed. Set up Moonlight again.",
                                )
                        }

                        override fun checkClientTrusted(
                            chain: Array<X509Certificate>,
                            authType: String,
                        ) = throw CertificateException()

                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    },
                ),
                null,
            )
        }

    companion object {
        /** Artemis, Apollo's Moonlight client, then Moonlight. */
        private val PACKAGES = listOf("com.limelight.noir", "com.limelight")
        private const val PREFIX = "moonlight:"
        private const val PORT = 47990
        private const val ADDRESS = "address"
        private const val TOKEN = "token"
        private const val CERTIFICATE = "certificate"
        private const val LIST_LIMIT = 4 shl 20
        private const val COVER_LIMIT = 16 shl 20

        fun isGame(game: Game): Boolean = game.romUri.startsWith(PREFIX)

        fun steamId(game: Game): String =
            if (isGame(game)) game.romUri.split('/').getOrNull(3).orEmpty() else ""

        /**
         * A shortcut to `game` in the first installed client: host UUID, app UUID (read by
         * Artemis), app ID (read by Moonlight) and name.
         */
        fun launch(game: Game, packages: PackageManager): android.content.Intent {
            val (host, app, id) = game.romUri.removePrefix(PREFIX).split('/')
            val client =
                PACKAGES.firstOrNull {
                    try {
                        packages.getPackageInfo(it, 0)
                        true
                    } catch (error: PackageManager.NameNotFoundException) {
                        false
                    }
                } ?: PACKAGES.last()
            return android.content
                .Intent()
                .setClassName(client, "com.limelight.ShortcutTrampoline")
                .putExtra("UUID", host)
                .putExtra("AppUUID", app)
                .putExtra("AppId", id)
                .putExtra("AppName", game.title)
        }
    }
}
