package io.github.landern.miel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Covers, front box art and screenshots from local sidecars, Steam, RomM and Libretro's thumbnail server,
 * kept in files/artwork/libretro-v1. Cached images are published before any request; downloads run
 * one at a time on a background thread, covers first.
 */
class ArtworkCache(
    private val context: Context,
    /** Runs on the main thread whenever an image becomes available. */
    private val changed: () -> Unit,
) {
    /** Image paths by kind and game ID. */
    private val paths = ConcurrentHashMap<String, String>()

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Increments with each update; a newer update stops an older one. */
    private val generation = AtomicInteger()
    private val notifications = MielNotifications.Channel()

    @Volatile private var closed = false

    fun coverPath(id: String): String = paths["${LibretroArt.BOXARTS}:$id"] ?: ""

    fun heroPath(id: String): String = paths["${LibretroArt.SNAPS}:$id"] ?: ""

    fun boxartPath(game: Game): String =
        paths["$FRONT_BOXART:${game.id}"]
            ?: if (game.rommId == 0) coverPath(game.id) else ""

    fun close() {
        closed = true
        notifications.cancel()
        worker.shutdownNow()
    }

    /** Stops pending downloads and removes downloaded artwork and lookup caches. */
    fun clear(done: (Boolean) -> Unit) {
        generation.incrementAndGet()
        notifications.cancel()
        worker.execute {
            val removed =
                try {
                    File(context.filesDir, "artwork/libretro-v1").deleteRecursively()
                } catch (error: IOException) {
                    Log.w(TAG, "Cannot remove artwork", error)
                    false
                }
            paths.clear()
            main.post {
                if (!closed) {
                    changed()
                    done(removed)
                }
            }
        }
    }

    /** Finds artwork for the ROM library's `games`. */
    fun update(games: List<Game>) {
        val request = generation.incrementAndGet()
        val ticket = notifications.begin()
        worker.execute {
            try {
                val directory = File(context.filesDir, "artwork/libretro-v1")
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw IOException("Cannot create the artwork cache")
                }
                publishCached(directory, games, request)
                notifyChanged()
                download(directory, games, request, ticket)
            } catch (error: Exception) {
                Log.w(TAG, "Artwork cache unavailable", error)
                if (current(request)) notifications.update(ticket, MielNotification(
                    "Artwork update failed", "Please try rescanning your library",
                    "Finished", 1f, finished = true, warning = true,
                ))
            }
        }
    }

    /** Caches supplied artwork, rereading local files on every update. */
    private fun fetchSource(
        directory: File,
        game: Game,
        kind: String,
        source: String,
        request: Int,
    ): Boolean {
        val recorded = File(directory, stem(game.id, kind) + ".source.txt")
        if (
            !source.startsWith("content:") &&
                paths.containsKey("$kind:${game.id}") &&
                recorded.isFile &&
                recorded.readText() == source
        ) {
            return true
        }
        return try {
            val bytes =
                if (source.startsWith("content:")) {
                    context.contentResolver.openInputStream(Uri.parse(source))?.use {
                        readImage(it)
                    } ?: throw IOException("Cannot open local artwork")
                } else fetchBytes(source, IMAGE_LIMIT)
            val file = cacheImage(directory, game.id, kind, game.system, source, bytes)
            if (current(request)) {
                paths["$kind:${game.id}"] = file.absolutePath
                notifyChanged()
            }
            true
        } catch (error: IOException) {
            Log.w(TAG, "Cannot load supplied artwork for ${game.id}", error)
            false
        }
    }

    private fun notifyChanged() {
        main.post { if (!closed) changed() }
    }

    private fun current(request: Int) = !closed && request == generation.get()

    private fun sourceFor(game: Game, kind: String): String =
        when (kind) {
            LibretroArt.BOXARTS -> game.cover
            FRONT_BOXART -> game.boxart
            else -> game.screenshot
        }

    private fun publishCached(directory: File, games: List<Game>, request: Int) {
        for (game in games) {
            if (!current(request)) return
            for (kind in KINDS) {
                paths.remove("$kind:${game.id}")
                val file = image(directory, game.id, kind)
                val recorded = File(directory, stem(game.id, kind) + ".source.txt")
                val previous = if (recorded.isFile) recorded.readText() else ""
                val source = sourceFor(game, kind)
                if (kind == FRONT_BOXART && source.isEmpty()) continue
                if (previous.startsWith("content:") && previous != source) continue
                if (valid(file)) paths["$kind:${game.id}"] = file.absolutePath
            }
        }
    }

    private fun download(directory: File, games: List<Game>, request: Int, ticket: Int) {
        val listings = HashMap<String, List<String>>()
        val tried = HashSet<String>()
        // Freeze the pending asset list: cached images and recorded misses do not inflate progress.
        val pending = KINDS.flatMap { kind -> games.map { it to kind } }.filter { (game, kind) ->
            if (!current(request)) return
            val source = sourceFor(game, kind)
            if (source.startsWith("content:")) {
                fetchSource(directory, game, kind, source, request)
                false
            } else if (hasMiss(directory, game, kind, source)) {
                false
            } else if (source.isNotEmpty()) {
                val recorded = File(directory, stem(game.id, kind) + ".source.txt")
                !(paths.containsKey("$kind:${game.id}") && recorded.isFile && recorded.readText() == source)
            } else {
                kind != FRONT_BOXART && !paths.containsKey("$kind:${game.id}") && game.system.isNotEmpty()
            }
        }
        if (pending.isEmpty() || !current(request)) return
        fun show(value: MielNotification) {
            if (current(request)) notifications.update(ticket, value)
        }
        show(MielNotification("Checking missing artwork", "Finding covers and screenshots"))
        var downloaded = 0
        pending.forEachIndexed { index, (game, kind) ->
            if (!current(request)) return
            val supplied = sourceFor(game, kind)
            val asset = when (kind) {
                LibretroArt.BOXARTS -> "Cover art"
                FRONT_BOXART -> "Front box art"
                else -> "Screenshot"
            }
            val provider = if (supplied.isEmpty()) "Libretro" else "Library source"
            show(MielNotification("Downloading: ${game.title}", "$asset · $provider",
                "${index + 1} of ${pending.size}", index.toFloat() / pending.size))
            val success = try {
                if (supplied.isNotEmpty()) {
                    fetchSource(directory, game, kind, supplied, request)
                } else {
                    val key = "$kind:${game.system}"
                    if (tried.add(key)) listings[key] = listing(directory, game.system, kind)
                    val name = match(listings[key], game)
                    if (name == null) {
                        false
                    } else {
                        val source = LibretroArt.imageUrl(game.system, kind, name)
                        val file = fetchImage(directory, game.id, kind, game.system, source)
                        if (current(request)) {
                            paths["$kind:${game.id}"] = file.absolutePath
                            notifyChanged()
                        }
                        true
                    }
                }
            } catch (error: IOException) {
                Log.w(TAG, "No artwork for ${game.id}", error)
                false
            }
            if (success) {
                downloaded++
                Files.deleteIfExists(File(directory, stem(game.id, kind) + ".miss").toPath())
            } else if (current(request) && !Thread.currentThread().isInterrupted) {
                File(directory, stem(game.id, kind) + ".miss").writeText(missSource(game, supplied))
            }
        }
        val missing = pending.size - downloaded
        show(MielNotification(
            if (missing == 0) "Library updated" else "Update finished",
            if (missing == 0) "$downloaded downloads complete" else "$downloaded downloaded · $missing unavailable",
            "${pending.size} of ${pending.size}", 1f, finished = true, warning = missing > 0,
            unavailableFraction = missing.toFloat() / pending.size,
        ))
    }

    private fun hasMiss(directory: File, game: Game, kind: String, source: String): Boolean {
        val miss = File(directory, stem(game.id, kind) + ".miss")
        return miss.isFile && miss.readText() == missSource(game, source)
    }

    private fun missSource(game: Game, source: String): String =
        if (source.isNotEmpty()) source else "${game.system}\n${game.romFilename}\n${game.region}"

    private companion object {
        const val TAG = "MielArtwork"
        const val FRONT_BOXART = "Box2DFront"
        val KINDS = listOf(LibretroArt.BOXARTS, FRONT_BOXART, LibretroArt.SNAPS)
        const val THREE_DS = "Nintendo - Nintendo 3DS"

        /**
         * The upper screen's height over its width, for systems whose screenshots Libretro stacks
         * as the upper screen over a lower one of the same height.
         */
        val UPPER_SCREENS = mapOf(THREE_DS to .6f, "Nintendo - Nintendo DS" to .75f)

        /** Regions tried, in order, for a filename without a region tag. */
        val REGIONS = listOf("Europe", "USA", "World", "Japan")

        const val DAY = 24L * 60 * 60 * 1000
        const val LIBRETRO_TIMEOUT = 2000
        const val LISTING_LIMIT = 8 shl 20
        const val IMAGE_LIMIT = 4 shl 20
        const val MAX_IMAGE_SIZE = 2048

        /**
         * The listing's file for `game`, in its own region or, without one, the first of REGIONS.
         */
        fun match(names: List<String>?, game: Game): String? {
            if (names == null) return null
            val title = RomNames.title(game.romFilename)
            if (game.region.isNotEmpty()) return LibretroArt.match(names, title, game.region)
            return REGIONS.firstNotNullOfOrNull { LibretroArt.match(names, title, it) }
        }

        fun fetchImage(
            directory: File,
            id: String,
            kind: String,
            system: String,
            source: String,
        ): File =
            cacheImage(
                directory,
                id,
                kind,
                system,
                source,
                fetchBytes(source, IMAGE_LIMIT, LIBRETRO_TIMEOUT, LIBRETRO_TIMEOUT),
            )

        private fun cacheImage(
            directory: File,
            id: String,
            kind: String,
            system: String,
            source: String,
            bytes: ByteArray,
        ): File {
            val temporary = File(directory, stem(id, kind) + ".tmp")
            temporary.writeBytes(bytes)
            if (!valid(temporary)) {
                Files.deleteIfExists(temporary.toPath())
                throw IOException("Invalid image")
            }
            val upper = UPPER_SCREENS[system]
            if (kind == LibretroArt.SNAPS && upper != null) cropUpperScreen(temporary, upper)
            val file = image(directory, id, kind)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(directory, stem(id, kind) + ".source.txt").writeText(source)
            return file
        }

        private fun readImage(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) return output.toByteArray()
                if (output.size() + count > IMAGE_LIMIT) throw IOException("Image exceeds 4 MiB")
                output.write(buffer, 0, count)
            }
        }

        fun stem(id: String, kind: String): String {
            if (!id.matches(Regex("[a-z0-9-]+"))) throw IOException("Invalid game ID")
            return when (kind) {
                LibretroArt.BOXARTS -> id
                FRONT_BOXART -> "$id-box2d-v1"
                else -> "$id-snap-v1"
            }
        }

        fun image(directory: File, id: String, kind: String) =
            File(directory, stem(id, kind) + ".png")

        /**
         * Keeps the upper screen of a stacked screenshot whose upper screen is `upper` as high as
         * it is wide, such as a 3DS's 400×240 over 320×240. Other shapes stay whole.
         */
        fun cropUpperScreen(file: File, upper: Float) {
            val source =
                BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IOException("Invalid screenshot")
            var screen: Bitmap? = null
            try {
                val ratio = source.height.toFloat() / source.width
                if (abs(ratio - 2 * upper) > .035f) return
                val height = (source.width * upper).roundToInt()
                val cropped = Bitmap.createBitmap(source, 0, 0, source.width, height)
                screen = cropped
                FileOutputStream(file).use { output ->
                    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("Cannot cache the upper screen")
                    }
                    output.fd.sync()
                }
            } finally {
                if (screen != null && screen !== source) screen.recycle()
                source.recycle()
            }
        }

        /** Whether `file` is a decodable image of at most 4 MiB and 2048 pixels a side. */
        fun valid(file: File): Boolean {
            if (!file.isFile || file.length() > IMAGE_LIMIT) return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth !in 1..MAX_IMAGE_SIZE || bounds.outHeight !in 1..MAX_IMAGE_SIZE) {
                return false
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
            bitmap.recycle()
            return true
        }

        /**
         * The image names in the system's directory listing, cached for a week. An older cached
         * listing is used when the server cannot be reached.
         */
        fun listing(directory: File, system: String, kind: String): List<String> {
            var prefix = if (system == THREE_DS) "3ds-index" else "index-" + RomNames.id(system)
            if (kind == LibretroArt.SNAPS) prefix += "-snaps"
            val index = File(directory, "$prefix.html")
            if (index.exists() && System.currentTimeMillis() - index.lastModified() < 7 * DAY) {
                return LibretroArt.filenames(index.readText())
            }
            try {
                val html =
                    String(
                        fetchBytes(
                            LibretroArt.listingUrl(system, kind),
                            LISTING_LIMIT,
                            LIBRETRO_TIMEOUT,
                            LIBRETRO_TIMEOUT,
                        ),
                    )
                if (LibretroArt.filenames(html).isEmpty()) {
                    throw IOException("Empty thumbnail listing")
                }
                val temporary = File(directory, "$prefix.tmp")
                temporary.writeText(html)
                Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return LibretroArt.filenames(html)
            } catch (error: IOException) {
                if (index.exists()) return LibretroArt.filenames(index.readText())
                throw error
            }
        }
    }
}
