package io.github.landern.miel

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * An optional RomM server: pairing through RomM's device flow, its games, and the in-game saves
 * kept in each game's "autosave" slot. The address, token and device ID live in the app's
 * preferences. Every call blocks; run them off the main thread.
 */
class RomM(private val context: Context) {
    private val preferences = context.getSharedPreferences("romm", Context.MODE_PRIVATE)

    /** The server's address, or an empty string. */
    val server: String
        get() = preferences.getString(SERVER, "") ?: ""

    val paired: Boolean
        get() = server.isNotEmpty() && token.isNotEmpty()

    val token: String
        get() = preferences.getString(TOKEN, "") ?: ""

    private val device: String
        get() = preferences.getString(DEVICE, "") ?: ""

    /** A pairing waiting for approval in RomM; `link` opens the approval page with the code. */
    class Pairing(
        val server: String,
        val code: String,
        val link: String,
        val deviceCode: String,
        val interval: Long,
    )

    /**
     * A game on the server, with the platform folder it sits in and its metadata: name, cover and
     * screenshot addresses, release year, first genre and 3DS title ID, each empty or 0 when RomM
     * has none.
     */
    class Rom(
        val id: Int,
        val filename: String,
        val folder: String,
        val name: String,
        val cover: String,
        val screenshot: String,
        val year: Int,
        val genre: String,
        val titleId: String,
        val boxart: String = "",
    )

    /** Bytes received, response size when known, and the latest transfer rate. */
    data class DownloadProgress(val received: Long, val total: Long, val bytesPerSecond: Double) {
        val fraction: Float
            get() =
                if (total > 0) (received.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else -1f

        val status: String
            get() {
                val percent = if (total > 0) " ${(fraction * 100).toInt()}%" else ""
                val amount =
                    if (total > 0) "${mb(received.toDouble())} / ${mb(total.toDouble())} MB"
                    else "${mb(received.toDouble())} MB"
                return "Downloading your game…$percent\n$amount · ${mb(bytesPerSecond)} MB/s"
            }

        private fun mb(bytes: Double) = String.format("%.1f", bytes / 1_000_000)
    }

    /** A sync session and the server's newest autosave of each game it covers, by game ID. */
    class Session(val id: Int, val saves: Map<Int, Save>)

    /** A server autosave: its ID, modification time and content hash. */
    class Save(val id: Int, val updated: String, val hash: String)

    /** Starts pairing with `address`. */
    fun pair(address: String): Pairing {
        val server = address.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
        val identifier =
            preferences.getString(IDENTIFIER, null)
                ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString(IDENTIFIER, it).apply()
                }
        val body =
            JSONObject()
                .put("client_device_identifier", identifier)
                .put("name", "miel on ${Build.MODEL}")
                .put("client", "miel")
                .put("platform", "android")
                .put(
                    "client_version",
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName,
                )
                .put("requested_scopes", JSONArray(SCOPES))
        val reply = JSONObject(String(request("POST", "$server/api/auth/device/init", body, "")))
        return Pairing(
            server,
            reply.getString("user_code"),
            server + reply.getString("verification_path_complete"),
            reply.getString("device_code"),
            reply.optLong("interval", 5),
        )
    }

    /**
     * Whether RomM approved `pairing`, keeping the token if so. Throws once it was refused or
     * expired.
     */
    fun approved(pairing: Pairing): Boolean {
        val body = JSONObject().put("device_code", pairing.deviceCode)
        val reply =
            try {
                JSONObject(
                    String(request("POST", "${pairing.server}/api/auth/device/token", body, "")),
                )
            } catch (error: Refused) {
                if ("authorization_pending" in error.message.orEmpty()) return false
                throw error
            }
        preferences
            .edit()
            .putString(SERVER, pairing.server)
            .putString(TOKEN, reply.getString("access_token"))
            .putString(DEVICE, reply.getString("device_id"))
            .apply()
        return true
    }

    /** Forgets the server, its token and every game's last sync; the install's identifier stays. */
    fun forget() {
        val identifier = preferences.getString(IDENTIFIER, null)
        preferences.edit().clear().putString(IDENTIFIER, identifier).apply()
    }

    /** Every game on the server, with the platform folder it sits in. */
    fun roms(progress: (String, Int, Int) -> Unit = { _, _, _ -> }): List<Rom> {
        val entries = ArrayList<JSONObject>()
        while (true) {
            val page = JSONObject(String(get("/api/roms?limit=500&offset=${entries.size}")))
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) entries.add(items.getJSONObject(i))
            progress(
                entries.lastOrNull()?.optString("name").orEmpty(),
                entries.size,
                page.optInt("total", entries.size),
            )
            if (items.length() == 0 || entries.size >= page.optInt("total")) break
        }
        val roms = ArrayList<Rom>()
        for (rom in entries) {
            val metadata = rom.getJSONObject("metadatum")
            val released =
                if (metadata.isNull("first_release_date")) 0L
                else metadata.getLong("first_release_date")
            roms.add(
                Rom(
                    rom.getInt("id"),
                    rom.getString("fs_name").let {
                        if (rom.optBoolean("has_multiple_files")) "$it.zip" else it
                    },
                    rom.getString("platform_fs_slug"),
                    text(rom, "name"),
                    asset(text(rom, "path_cover_large")),
                    asset(rom.getJSONArray("merged_screenshots").optString(0)),
                    if (released == 0L) 0
                    else Instant.ofEpochMilli(released).atZone(ZoneOffset.UTC).year,
                    metadata.getJSONArray("genres").optString(0),
                    text(rom, "title_id"),
                    asset(boxartPath(rom)),
                ),
            )
        }
        return roms
    }

    /** The address of a server asset at `path`, with its query encoded; empty for no path. */
    private fun asset(path: String): String {
        if (!path.startsWith("/")) return ""
        val query = path.substringAfter('?', "")
        return server +
            path.substringBefore('?') +
            if (query.isEmpty()) "" else "?" + encode(query).replace("%3D", "=")
    }

    /** The address game `id`'s file `filename` downloads from; see download. */
    fun link(id: Int, filename: String) = "$server/api/roms/$id/content/${encode(filename)}"

    /** The content hash of game `id`'s save when it last synced here, or an empty string. */
    fun synced(id: Int): String = preferences.getString("$SYNCED$id", "") ?: ""

    /** Records that game `id`'s save here and on the server both hold `hash`. */
    fun synced(id: Int, hash: String) {
        preferences.edit().putString("$SYNCED$id", hash).apply()
    }

    /** Opens a sync session for the in-game saves `saves`, by game ID; a file may not exist. */
    fun negotiate(saves: Map<Int, File>): Session {
        val states = JSONArray()
        for ((id, file) in saves) {
            if (!file.isFile) continue
            states.put(
                JSONObject()
                    .put("rom_id", id)
                    .put("file_name", file.name)
                    .put("slot", SLOT)
                    .put("content_hash", hash(file))
                    .put("updated_at", Instant.ofEpochMilli(file.lastModified()).toString())
                    .put("file_size_bytes", file.length()),
            )
        }
        val body =
            JSONObject()
                .put("device_id", device)
                .put("saves", states)
                .put("rom_ids", JSONArray(saves.keys.toList()))
        val reply = JSONObject(String(request("POST", "$server/api/sync/negotiate", body, token)))
        val operations = reply.getJSONArray("operations")
        val serverSaves = HashMap<Int, Save>()
        for (i in 0 until operations.length()) {
            val operation = operations.getJSONObject(i)
            if (operation.optString("slot") != SLOT) continue
            if (operation.isNull("save_id")) continue
            serverSaves[operation.getInt("rom_id")] =
                Save(
                    operation.getInt("save_id"),
                    text(operation, "server_updated_at"),
                    text(operation, "server_content_hash"),
                )
        }
        return Session(reply.getInt("session_id"), serverSaves)
    }

    /** Replaces `file` with the server's `save`, keeping the old one beside it as .bak. */
    fun pull(session: Session, save: Save, file: File) {
        val bytes =
            get(
                "/api/saves/${save.id}/content?device_id=${encode(device)}&session_id=${session.id}",
            )
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (file.isFile) {
            Files.copy(
                file.toPath(),
                File(file.parentFile, file.name + ".bak").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        request(
            "POST",
            "$server/api/saves/${save.id}/downloaded",
            JSONObject().put("device_id", device),
            token,
        )
    }

    /**
     * Uploads `file` as game `id`'s autosave, written by `emulator`, once the session or the player
     * chose this save. It is added to the slot beside the server's; overwrite only skips the
     * server's check for a newer save.
     */
    fun push(session: Session, id: Int, file: File, emulator: String) {
        val query =
            "rom_id=$id&slot=$SLOT&emulator=${encode(emulator)}&device_id=${encode(device)}" +
                "&session_id=${session.id}&overwrite=true"
        val boundary = "miel-" + UUID.randomUUID()
        val connection = open("POST", "$server/api/saves?$query", token)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.outputStream.use { output ->
            output.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"saveFile\"; " +
                        "filename=\"${file.name.replace("\"", "")}\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n")
                    .toByteArray(),
            )
            file.inputStream().use { it.copyTo(output) }
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }
        receive(connection).close()
    }

    /** Closes a sync session. */
    fun complete(session: Session, completed: Int, failed: Int) {
        request(
            "POST",
            "$server/api/sync/sessions/${session.id}/complete",
            JSONObject().put("operations_completed", completed).put("operations_failed", failed),
            token,
        )
    }

    private fun get(path: String): ByteArray =
        receive(open("GET", server + path, token)).use { it.readBytes() }

    private fun request(method: String, url: String, body: JSONObject, token: String): ByteArray {
        val connection = open(method, url, token)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return receive(connection).use { it.readBytes() }
    }

    /** A request RomM answered with an error status. */
    class Refused(message: String) : IOException(message)

    companion object {
        /** The romUri prefix, before RomM's ID, of a game only on the server. */
        const val URI = "romm:"
        const val SLOT = "autosave"
        private const val SERVER = "server"
        private const val TOKEN = "token"
        private const val DEVICE = "device"
        private const val IDENTIFIER = "identifier"
        private const val SYNCED = "synced:"
        private const val CONNECT_TIMEOUT = 3000
        private const val READ_TIMEOUT = 8000
        private val SCOPES =
            listOf(
                "roms.read",
                "platforms.read",
                "assets.read",
                "assets.write",
                "devices.read",
                "devices.write",
            )

        /** Cached Box (2D, front) resource, with an unencoded version query for asset(). */
        internal fun boxartPath(rom: JSONObject): String {
            val path =
                listOf("ss_metadata", "launchbox_metadata").firstNotNullOfOrNull { key ->
                    rom.optJSONObject(key)
                        ?.let { text(it, "box2d_path") }
                        ?.takeIf { it.isNotEmpty() }
                } ?: return ""
            val updated = text(rom, "updated_at")
            return "/assets/romm/resources/$path" + if (updated.isEmpty()) "" else "?v=$updated"
        }

        /**
         * What to do with a game's save, from the content hashes of the local save, the server's
         * newest and the save at their last sync, each empty when there is none: "upload",
         * "download", "conflict" or "no_op". Two differing saves without a common last sync are a
         * conflict.
         */
        fun decide(local: String, server: String, synced: String): String =
            when {
                local == server -> "no_op"
                local.isEmpty() -> "download"
                server.isEmpty() -> "upload"
                local != synced && server != synced -> "conflict"
                local != synced -> "upload"
                else -> "download"
            }

        /**
         * Downloads a game from `link` with the device `token` to `target`, reporting bytes
         * received, response size and transfer rate. Needs no preferences, so another process can
         * call it.
         */
        fun download(
            link: String,
            token: String,
            target: File,
            progress: (DownloadProgress) -> Unit,
        ) {
            val connection = open("GET", link, token)
            val size = connection.contentLengthLong
            val temporary = File(target.parentFile, target.name + ".part")
            try {
                receive(connection).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var received = 0L
                        var sampledBytes = 0L
                        var sampledAt = System.nanoTime()
                        var speed = 0.0
                        fun report() {
                            val now = System.nanoTime()
                            if (received > sampledBytes) {
                                speed =
                                    (received - sampledBytes) * 1_000_000_000.0 / (now - sampledAt)
                            }
                            progress(DownloadProgress(received, size, speed))
                            sampledBytes = received
                            sampledAt = now
                        }
                        report()
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                            output.write(buffer, 0, count)
                            received += count
                            if (System.nanoTime() - sampledAt >= 500_000_000L) report()
                        }
                        report()
                        output.fd.sync()
                    }
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }

        private fun open(method: String, url: String, token: String): HttpURLConnection {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
            return connection
        }

        /** The response body; a refusal throws Refused with the server's detail. */
        private fun receive(connection: HttpURLConnection): InputStream {
            val status = connection.responseCode
            if (status in 200..299) return connection.inputStream
            val detail = connection.errorStream?.use { String(it.readBytes()) }.orEmpty()
            connection.disconnect()
            throw Refused("RomM answered $status: ${detail.take(200)}")
        }

        private fun text(json: JSONObject, key: String) =
            if (json.isNull(key)) "" else json.getString(key)

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /**
         * A save's content hash as RomM computes it: for a ZIP archive the MD5 of its files' sorted
         * "name:MD5" lines, folders left out, otherwise the file's MD5.
         */
        fun hash(file: File): String {
            val archive =
                try {
                    ZipFile(file)
                } catch (error: ZipException) {
                    return file.inputStream().use(::md5)
                }
            return archive.use { zip ->
                val lines =
                    zip.entries()
                        .asSequence()
                        .filter { !it.isDirectory }
                        .sortedBy { it.name }
                        .map { entry -> entry.name + ":" + zip.getInputStream(entry).use(::md5) }
                MessageDigest.getInstance("MD5")
                    .digest(lines.joinToString("\n").toByteArray())
                    .toHex()
            }
        }

        private fun md5(input: InputStream): String {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().toHex()
        }
    }
}
