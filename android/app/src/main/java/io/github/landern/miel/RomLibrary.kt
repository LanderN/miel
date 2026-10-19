package io.github.landern.miel

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A game in the ROM library. */
data class Game(
    val id: String,
    val title: String,
    val platform: String,
    val region: String,
    /** Libretro's name for the platform. */
    val system: String,
    /** A content:// document, or romm:<ID> for a game only on the RomM server. */
    val romUri: String,
    val romFilename: String,
    /** The game's ID on the RomM server, or 0. */
    val rommId: Int = 0,
    /** Supplied artwork URIs, release year and genre. */
    val cover: String = "",
    val screenshot: String = "",
    val year: Int = 0,
    val genre: String = "",
    /** RomM's 3DS title ID in hexadecimal, when it has one. */
    val titleId: String = "",
    val romDirectory: String = "",
) {
    /** This game with `romm`'s ID, name and metadata. */
    fun linked(romm: Game) =
        copy(
            rommId = romm.rommId,
            title = romm.title,
            cover = romm.cover,
            screenshot = romm.screenshot,
            year = romm.year,
            genre = romm.genre,
            titleId = romm.titleId,
        )

    /** The embedded core that runs the game, if any. */
    val core: Core?
        get() = Core.of(platform)

    val playable: Boolean
        get() = romUri.isNotEmpty() && core?.accepts(romFilename) == true
}

/**
 * The games found in the chosen ROM folder, by file name, through the Storage Access Framework. The
 * library is saved in files/rom-library.json and replaced only by a complete scan.
 */
class RomLibrary(
    private val context: Context,
    /** Receives the games on the main thread after start() and every scan. */
    private val changed: (List<Game>) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scanning = AtomicBoolean()
    private val saved = AtomicFile(File(context.filesDir, "rom-library.json"))
    private val romm = RomM(context)
    private val notifications = MielNotifications.Channel()

    /** The current library. */
    @Volatile
    var games: List<Game> = emptyList()
        private set

    /** The last scan's result for Scan report. */
    @Volatile private var report = "Choose a folder to detect your games."

    @Volatile private var closed = false

    init {
        if (saved.baseFile.exists()) {
            try {
                val cached = JSONObject(String(saved.readFully()))
                val list = cached.getJSONArray("games")
                games = List(list.length()) { game(list.getJSONObject(it)) }
                report = cached.optString("report", cached.optString("status"))
            } catch (error: IOException) {
                report = UNREADABLE
            } catch (error: JSONException) {
                report = UNREADABLE
            }
        }
    }

    /** Runs on the main thread. */
    fun start() {
        changed(games)
    }

    fun report(): String = report

    fun busy(): Boolean = scanning.get()

    /** The chosen folder's tree URI, or an empty string. */
    fun tree(): String = preferences().getString(TREE, "") ?: ""

    fun close() {
        closed = true
        notifications.cancel()
        worker.shutdownNow()
    }

    private fun preferences() = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private fun publish(games: List<Game>) {
        main.post { if (!closed) changed(games) }
    }

    /**
     * Scans `tree`, if any, and the paired RomM server in the background. A failed folder scan
     * keeps the previous library; an unreachable server keeps its games from the last scan.
     */
    fun scan(tree: Uri?) {
        if (!scanning.compareAndSet(false, true)) return
        report = "Scanning your folder…"
        worker.execute {
            try {
                val scan = if (tree != null) scanFolder(tree) else Scan(null)
                if (romm.paired) merge(scan)
                scan.found.replaceAll { game -> scan.metadata[game.id]?.apply(game) ?: game }
                scan.found.sortBy { it.title.lowercase() }
                if (closed) return@execute
                save(scan)
                publish(games)
            } catch (error: Exception) {
                val ticket = notifications.begin()
                if (!closed) notifications.update(ticket, MielNotification(
                    "Library update failed", "Your previous library was kept",
                    "Finished", 1f, finished = true, warning = true,
                ))
                report =
                    "Scan failed. Your previous library was kept. Re-select the folder" +
                        " if access changed.\n\n" +
                        error.message
                Log.w("MielLibrary", "Scan failed", error)
                publish(games)
            } finally {
                scanning.set(false)
            }
        }
    }

    private class Scan(val tree: Uri?) {
        val seen = HashSet<String>()
        val found = ArrayList<Game>()
        val metadata = HashMap<String, LocalMetadata>()
        var entries = 0
        var skipped = 0
        var romm = ""
    }

    /**
     * Adds the RomM server's games: a local game with the same platform and file name gets the
     * server's ID, and the rest are listed as RomM games.
     */
    private fun merge(scan: Scan) {
        val ticket = notifications.begin()
        fun show(value: MielNotification) {
            if (!closed) notifications.update(ticket, value)
        }
        show(MielNotification("Fetching game metadata", "RomM · Connecting"))
        val remote =
            try {
                romm.roms { title, completed, total ->
                    show(MielNotification(
                        title.ifEmpty { "Fetching game metadata" }, "Metadata · RomM",
                        "$completed of $total", if (total > 0) completed.toFloat() / total else -1f,
                    ))
                }.mapNotNull { rom ->
                    val platform = RomNames.platform(rom.filename, rom.folder)
                    if (Core.of(platform) == null) null else romGame(romm.server, rom, platform)
                }
            } catch (error: IOException) {
                Log.w("MielLibrary", "RomM unreachable", error)
                show(MielNotification("Metadata update failed", "RomM unavailable · Kept saved metadata",
                    "Finished", 1f, finished = true, warning = true))
                scan.romm = "RomM was unreachable; its games are from the last scan."
                val links = games.filter { it.rommId != 0 }.associateBy { it.id }
                scan.found.replaceAll { game -> links[game.id]?.let(game::linked) ?: game }
                scan.found.addAll(games.filter { it.romUri.startsWith(RomM.URI) })
                return
            }
        show(MielNotification("Metadata updated", "${remote.size} games refreshed",
            "${remote.size} of ${remote.size}", 1f, finished = true))
        val local =
            scan.found.withIndex().associate { (i, game) ->
                (game.platform to game.romFilename) to i
            }
        var listed = 0
        for (game in remote) {
            val match =
                local[game.platform to game.romFilename]
                    ?: if (
                        game.platform == "PlayStation" && game.romFilename.endsWith(".zip", true)
                    )
                        local[game.platform to (game.romFilename.dropLast(4) + ".m3u")]
                    else null
            if (match != null) {
                scan.found[match] = scan.found[match].linked(game)
            } else {
                scan.found.add(game)
                listed++
            }
        }
        scan.romm = "RomM: ${remote.size} games, $listed of them only on the server."
    }

    private fun scanFolder(tree: Uri): Scan {
        val scan = Scan(tree)
        val root = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        val rootName =
            context.contentResolver
                .query(rootUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: ""
        visit(scan, root, rootName, 0)
        val tracks = HashSet<String>()
        for (game in scan.found.filter { it.platform == "PlayStation" }) {
            try {
                tracks.addAll(
                    DiscCopy.references(
                        context,
                        Uri.parse(game.romUri),
                        Uri.parse(game.romDirectory),
                    ),
                )
            } catch (error: IOException) {
                Log.w("MielLibrary", "Cannot inspect disc references", error)
            }
        }
        scan.found.removeAll { it.romUri in tracks }
        return scan
    }

    /** Saves the scan as the library. */
    private fun save(scan: Scan) {
        val status = "${scan.found.size} games · ${scan.skipped} skipped · Filename detection"
        val completed =
            status +
                (if (scan.romm.isEmpty()) "" else "\n\n" + scan.romm) +
                "\n\nSupported cartridge files are detected by extension. ISO/RVZ/CHD/PBP/EXE/BIN/CUE/M3U" +
                " and ZIP need a recognized platform folder. Other archives, CIA installers," +
                " referenced disc tracks, hidden and empty files are skipped. Detection does not validate" +
                " or launch a ROM."
        val result =
            JSONObject()
                .put("games", JSONArray(scan.found.map(::json)))
                .put("status", status)
                .put("source", scan.tree?.toString().orEmpty())
                .put("report", completed)
        var output: FileOutputStream? = null
        try {
            output = saved.startWrite()
            output.write(result.toString().toByteArray())
            saved.finishWrite(output)
        } catch (error: IOException) {
            if (output != null) saved.failWrite(output)
            throw error
        }
        if (scan.tree != null) preferences().edit().putString(TREE, scan.tree.toString()).apply()
        games = scan.found.toList()
        report = completed
    }

    /** Adds the games in `document`, a folder at `folder` below the root, and its subfolders. */
    private fun visit(scan: Scan, document: String, folder: String, depth: Int) {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException()
        if (!scan.seen.add(document)) return
        if (depth > MAX_DEPTH || scan.seen.size > MAX_ENTRIES || scan.found.size > MAX_GAMES) {
            throw IOException(
                "Folder exceeds scan limits (32 levels / 20,000 entries / 5,000 games)",
            )
        }
        val tree = scan.tree ?: return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, document)
        val columns =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
            )
        val cursor =
            context.contentResolver.query(children, columns, null, null, null)
                ?: throw IOException("Folder provider returned no result")
        val siblings = HashMap<String, String>()
        val folderGames = ArrayList<Game>()
        cursor.use {
            while (cursor.moveToNext()) {
                if (closed) throw InterruptedIOException()
                if (++scan.entries > MAX_ENTRIES) throw IOException("Folder exceeds 20,000 entries")
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (name == null || name.startsWith(".")) {
                    scan.skipped++
                    continue
                }
                if (cursor.getString(2) == Document.MIME_TYPE_DIR) {
                    visit(scan, id, "$folder/$name", depth + 1)
                    continue
                }
                siblings[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                val platform = RomNames.platform(name, folder)
                val empty = !cursor.isNull(3) && cursor.getLong(3) == 0L
                if (platform.isEmpty() || empty) {
                    scan.skipped++
                    continue
                }
                if (!scan.seen.add("file:$id")) continue
                if (scan.found.size >= MAX_GAMES) throw IOException("Folder exceeds 5,000 games")
                val found =
                    game(tree, id, name, platform)
                        .copy(
                            romDirectory =
                                DocumentsContract.buildDocumentUriUsingTree(tree, document)
                                    .toString(),
                        )
                scan.found.add(found)
                folderGames.add(found)
            }
        }
        for (game in folderGames) {
            scan.metadata[game.id] = LocalMetadata.read(context, game, siblings)
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_ENTRIES = 20_000
        const val MAX_GAMES = 5_000
        const val TREE = "tree"
        const val UNREADABLE = "Saved library could not be read. Choose the folder again."

        /**
         * A scanned game. Its ID hashes the provider and document ID, which a rescan or an ancestor
         * folder keeps.
         */
        fun game(tree: Uri, document: String, filename: String, platform: String) =
            Game(
                id = RomNames.id(tree.authority + ":" + document),
                title = RomNames.title(filename),
                platform = platform,
                region = RomNames.region(filename),
                system = RomNames.system(platform),
                romUri = DocumentsContract.buildDocumentUriUsingTree(tree, document).toString(),
                romFilename = filename,
            )

        /** A game only on the RomM server at `server`; its ID hashes the server and RomM's ID. */
        fun romGame(server: String, rom: RomM.Rom, platform: String) =
            Game(
                id = RomNames.id("$server:${rom.id}"),
                title = rom.name.ifEmpty { RomNames.title(rom.filename) },
                platform = platform,
                region = RomNames.region(rom.filename),
                system = RomNames.system(platform),
                romUri = RomM.URI + rom.id,
                romFilename = rom.filename,
                rommId = rom.id,
                cover = rom.cover,
                screenshot = rom.screenshot,
                year = rom.year,
                genre = rom.genre,
                titleId = rom.titleId,
            )

        /** A game as rom-library.json stores it. */
        fun game(json: JSONObject): Game {
            val platform = json.optString("platform")
            return Game(
                id = json.optString("id"),
                title = json.optString("title"),
                platform = platform,
                region = json.optString("region"),
                system = json.optString("libretro_system", RomNames.system(platform)),
                romUri = json.optString("rom_uri"),
                romFilename = json.optString("rom_filename"),
                rommId = json.optInt("romm_id"),
                cover = json.optString("cover"),
                screenshot = json.optString("screenshot"),
                year = json.optInt("year"),
                genre = json.optString("genre"),
                titleId = json.optString("title_id"),
                romDirectory = json.optString("rom_directory"),
            )
        }

        fun json(game: Game): JSONObject =
            JSONObject()
                .put("id", game.id)
                .put("title", game.title)
                .put("platform", game.platform)
                .put("region", game.region)
                .put("libretro_system", game.system)
                .put("rom_uri", game.romUri)
                .put("rom_filename", game.romFilename)
                .put("romm_id", game.rommId)
                .put("cover", game.cover)
                .put("screenshot", game.screenshot)
                .put("year", game.year)
                .put("genre", game.genre)
                .put("title_id", game.titleId)
                .put("rom_directory", game.romDirectory)
    }
}

