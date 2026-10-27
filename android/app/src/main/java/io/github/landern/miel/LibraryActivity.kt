package io.github.landern.miel

import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import io.github.landern.miel.UiSounds.Cue
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * The library on the upper display, and everything around it: the lower screen's artwork, the ROM
 * library, the app list, sounds, settings and launches. Input becomes a Command; see Session.
 */
class LibraryActivity : ComponentActivity() {
    private lateinit var session: Session
    private lateinit var sounds: UiSounds
    private lateinit var artwork: ArtworkCache
    private lateinit var library: RomLibrary
    private lateinit var apps: AppList
    private val displayListener = Displays.Listener(this) { ArtworkActivity.show(this) }
    private var overlay by mutableStateOf<Overlay?>(null)

    /** Bumped whenever new artwork is cached. */
    private var artworkVersion by mutableIntStateOf(0)
    private val images = LruCache<String, ImageBitmap>(32)
    private var keyDirection: Command? = null
    private var axisDirection: Command? = null
    private var activeDirection: Command? = null
    private var directionRepeat: Job? = null
    private lateinit var romm: RomM
    private val updates by lazy { AppUpdates(applicationContext) }
    private var updateRelease: AppUpdates.Release? = null
    private val updatePermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (packageManager.canRequestPackageInstalls()) installUpdate()
            else
                overlay =
                    Overlay.Update(
                        "Allow miel to install updates in Android Settings, then try again.",
                        "Install update",
                    )
        }
    private val updateInstaller =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            overlay =
                Overlay.Update(
                    "Installed version: ${updates.version}. You can check GitHub for updates again.",
                    "Check again",
                )
        }

    /** Pairing, or the save sync before a launch; closing its overlay cancels it. */
    private var work: Job? = null

    /** The sync SaveConflict waits for. */
    private var conflict: Sync? = null

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data
                ?.takeIf { result.resultCode == RESULT_OK }
                ?.let { data ->
                    data.data?.let { folderChosen(it, data.flags) }
                }
        }
    private val savePermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = overlay as? Overlay.SaveAccess ?: return@registerForActivityResult
            if (SaveStorage.available()) {
                overlay = null
                syncAndStart(pending.game)
            }
        }
    private val homeRole =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        current = this
        session = Session(File(filesDir, "sessions-v1.json"))
        sounds = UiSounds(this)
        artwork = ArtworkCache(applicationContext) { artworkVersion++ }
        library =
            RomLibrary(applicationContext) { scanned ->
                // The library lists embedded games and DroidDeck exports.
                val games = scanned.filter { it.playable }
                session.replaceGames(games)
                artwork.update(games)
            }
        apps =
            AppList(applicationContext) {
                images.evictAll()
                session.replaceApps(apps.apps)
            }
        romm = RomM(applicationContext)
        library.start()
        apps.start()
        pushSaves()
        volumeControlStream = AudioManager.STREAM_MUSIC
        Displays.immersive(window)
        onBackPressedDispatcher.addCallback(this) { command(Command.B) }
        val art = Art(::cover) { app -> image(apps.iconPath(app.id)) }
        setContent {
            Library(session, overlay, art, ::command)
            val hero = hero()
            SideEffect { ArtworkActivity.update(hero) }
        }
        displayListener.register()
        ArtworkActivity.show(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ArtworkActivity.reclaim()
        ArtworkActivity.close()
        ArtworkActivity.show(this)
    }

    override fun onResume() {
        super.onResume()
        ArtworkActivity.dim(false)
        sounds.setActive(true)
        ArtworkActivity.show(this)
    }

    override fun onPause() {
        ArtworkActivity.dim(true)
        sounds.setActive(false)
        keyDirection = null
        axisDirection = null
        activeDirection = null
        directionRepeat?.cancel()
        directionRepeat = null
        session.command(Command.Suspend)
        super.onPause()
    }

    override fun onDestroy() {
        if (current == this) current = null
        library.close()
        apps.close()
        artwork.close()
        sounds.release()
        ArtworkActivity.close()
        displayListener.unregister()
        super.onDestroy()
    }

    /** Decodes `path` at about a tile's size, keeping up to 32 images. */
    private fun image(path: String): ImageBitmap? =
        if (path.isEmpty()) null
        else
            images[path]
                ?: HeroView.decode(path, TILE_PIXELS, TILE_PIXELS)?.asImageBitmap()?.also {
                    images.put(path, it)
                }

    private fun cover(game: Game): Cover? {
        artworkVersion
        image(artwork.coverPath(game.id))?.let {
            return Cover(it, boxart = true)
        }
        return image(artwork.heroPath(game.id))?.let { Cover(it, boxart = false) }
    }

    /** The selected setting, game or app, for the lower screen. */
    private fun hero(): Hero {
        (overlay as? Overlay.Settings)?.let {
            val detail = settingsDetail(it, session)
            return Hero(detail.title, detail.context, "", settings = detail)
        }
        artworkVersion
        return if (session.tab == Tab.APPS) {
            session.app?.let {
                Hero(it.label, session.appScreen.label, apps.iconPath(it.id), icon = true)
            } ?: Hero("No apps", "", "")
        } else {
            session.game?.let { game ->
                val boxart = artwork.coverPath(game.id)
                val detail =
                    listOf(game.platform, if (game.year > 0) "${game.year}" else "", game.genre)
                        .filter { it.isNotEmpty() }
                        .joinToString(" · ")
                Hero(
                    game.title,
                    detail,
                    boxart.ifEmpty { artwork.heroPath(game.id) },
                    boxart = boxart.isNotEmpty(),
                )
            } ?: Hero(if (session.games.isEmpty()) "No games" else "No recent games", "", "")
        }
    }

    /** Runs `command`, starting games and opening apps, and plays the sound for the change. */
    private fun command(command: Command) {
        overlay?.let {
            return overlayCommand(it, command)
        }
        if (session.error == UNPLAYABLE) session.error = ""
        val command =
            if (command == Command.A && session.tab != Tab.APPS && session.games.isEmpty())
                Command.Select
            else command
        if (
            command == Command.A &&
                session.tab != Tab.APPS &&
                session.order.isEmpty() &&
                session.games.isNotEmpty()
        ) {
            session.command(
                Command.ChooseTab(session.tabs.firstOrNull { it.platform != null } ?: return),
            )
            sounds.play(Cue.NAVIGATE)
            return
        }
        if (session.tab == Tab.APPS) {
            when (command) {
                Command.A,
                Command.X -> return openApp(other = command == Command.X)
                // A touched app opens.
                is Command.Pick ->
                    if (command.index in session.apps.indices) {
                        session.command(command)
                        return openApp(other = false)
                    }
                else -> {}
            }
        }
        if (
            session.menu &&
                !session.confirm &&
                (command == Command.UploadRom || command == Command.PreviousTab)
        ) {
            session.game?.takeIf { it.deletable && it.rommId == 0 }?.let(::uploadRom)
            return
        }
        if (
            session.menu &&
                !session.confirm &&
                (command == Command.DeleteRom || command == Command.NextTab)
        ) {
            session.game
                ?.takeIf { it.deletable }
                ?.let {
                    overlay = Overlay.DeleteRom(it)
                    sounds.play(Cue.OPEN)
                }
            return
        }
        if (command == Command.Y && session.menu && !session.confirm) {
            session.game?.core?.let { overlay = Overlay.Settings(core = it, perGame = true) }
            sounds.play(Cue.OPEN)
            return
        }
        if (command == Command.Select) {
            if (!session.menu) openSettings()
            return
        }
        if (command == Command.A && session.tab != Tab.APPS) {
            val game = session.game ?: return
            if (!game.playable) return fail(UNPLAYABLE)
            if (DroidDeck.isGame(game)) return openDroidDeck(game)
            if (!session.menu || session.confirm) return syncAndStart(game)
        }
        if (
            session.menu &&
                !session.confirm &&
                command !in listOf(Command.A, Command.B, Command.X, Command.Suspend)
        )
            return
        val before = Status(session)
        session.command(command)
        cue(before, command)?.let(sounds::play)
    }

    /** The parts of the session whose changes have a sound. */
    private data class Status(
        val tab: Tab,
        val selected: Int,
        val appSelected: Int,
        val menu: Boolean,
        val confirm: Boolean,
    ) {
        constructor(
            s: Session,
        ) : this(s.tab, s.selected, s.appSelected, s.menu, s.confirm)
    }

    private fun cue(before: Status, command: Command): Cue? {
        val after = Status(session)
        return when {
            command == Command.Suspend -> null
            before.selected != after.selected || before.appSelected != after.appSelected ->
                Cue.NAVIGATE
            !before.confirm && after.confirm -> Cue.CONFIRM
            before.menu && !after.menu -> Cue.BACK
            !before.menu && after.menu -> Cue.OPEN
            before.tab != after.tab -> Cue.NAVIGATE
            session.error.isNotEmpty() && (command == Command.A || command == Command.B) ->
                Cue.ERROR
            else -> null
        }
    }

    private fun fail(error: String) {
        session.error = error
        sounds.play(Cue.ERROR)
    }

    private fun overlayCommand(overlay: Overlay, command: Command) {
        when (overlay) {
            is Overlay.Settings -> {
                val indices = overlay.core?.settingIndices(overlay.perGame) ?: SETTINGS.indices
                fun change(step: Int) {
                    val core = overlay.core ?: return
                    if (overlay.selected !in core.settingOptions.indices) return
                    session.changeOption(core, overlay.selected, step, overlay.perGame)
                    sounds.play(if (session.error.isEmpty()) Cue.CONFIRM else Cue.ERROR)
                }
                fun choose(index: Int) {
                    if (index !in indices) return
                    this.overlay = overlay.copy(selected = index)
                    if (
                        overlay.core == Core.AZAHAR &&
                            !overlay.perGame &&
                            index == overlay.core.settingOptions.size
                    ) {
                        clearShaderCache()
                    } else if (overlay.core != null) {
                        session.changeOption(overlay.core, index, 1, overlay.perGame)
                        sounds.play(if (session.error.isEmpty()) Cue.CONFIRM else Cue.ERROR)
                    } else settingChosen(index)
                }
                when (command) {
                    is Command.FocusSetting -> {
                        if (command.index in indices) {
                            this.overlay = overlay.copy(selected = command.index)
                            sounds.play(Cue.NAVIGATE)
                        }
                    }
                    Command.Up,
                    Command.Down -> {
                        val step = if (command == Command.Up) -1 else 1
                        val selected = (overlay.selected + step).coerceIn(indices)
                        if (selected != overlay.selected) {
                            this.overlay = overlay.copy(selected = selected)
                            sounds.play(Cue.NAVIGATE)
                        }
                    }
                    Command.A -> choose(overlay.selected)
                    is Command.GameRows ->
                        if (overlay.core == null) {
                            session.setGameRows(command.value)
                            this.overlay = overlay.copy(selected = 0)
                            sounds.play(Cue.CONFIRM)
                        }
                    Command.Left,
                    Command.Right ->
                        if (overlay.core != null) change(if (command == Command.Left) -1 else 1)
                        else if (overlay.selected == 0)
                            session.setGameRows(if (command == Command.Left) 1 else 2)
                    is Command.Pick -> choose(command.index)
                    Command.B,
                    Command.Select -> {
                        if (overlay.core != null && !overlay.perGame) {
                            this.overlay =
                                Overlay.Settings(
                                    selected = CORE_SETTINGS_START + overlay.core.ordinal,
                                )
                            sounds.play(Cue.BACK)
                        } else close()
                    }
                    else -> {}
                }
            }
            is Overlay.DeleteRom ->
                when (command) {
                    Command.A -> {
                        this.overlay = Overlay.Message("Deleting ROM", overlay.game.romFilename)
                        library.delete(overlay.game) { error ->
                            this.overlay =
                                if (error == null) null else Overlay.Message("Delete ROM", error)
                            sounds.play(if (error == null) Cue.CONFIRM else Cue.ERROR)
                        }
                    }
                    Command.B -> close()
                    else -> {}
                }
            is Overlay.SaveAccess ->
                when (command) {
                    Command.A ->
                        savePermission.launch(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    Command.B -> close()
                    else -> {}
                }
            is Overlay.Update ->
                when (command) {
                    Command.A ->
                        when (overlay.action) {
                            "Download update",
                            "Download again" -> downloadUpdate()
                            "Install update" -> installUpdate()
                            "Check again" -> checkUpdates()
                        }
                    Command.B -> close()
                    else -> {}
                }
            is Overlay.RomMAddress ->
                when (command) {
                    Command.A -> pair(overlay.address.text.toString())
                    Command.B -> close()
                    else -> {}
                }
            is Overlay.RomMPairing -> if (command == Command.B) close()
            is Overlay.RomMPaired ->
                when (command) {
                    Command.Y -> pair(overlay.server)
                    Command.X -> {
                        romm.forget()
                        close()
                        rescan()
                    }
                    Command.A,
                    Command.B -> close()
                    else -> {}
                }
            is Overlay.SaveConflict -> {
                val found = conflict ?: return close()
                when (command) {
                    Command.A,
                    Command.X -> {
                        this.overlay = null
                        val keep = if (command == Command.A) "download" else "upload"
                        work = lifecycleScope.launch { resolve(found, keep) }
                    }
                    Command.B -> close()
                    else -> {}
                }
            }
            is Overlay.Message ->
                when (command) {
                    Command.Up,
                    Command.Down -> {
                        val step = if (command == Command.Up) -1 else 1
                        lifecycleScope.launch {
                            overlay.scroll.scrollBy(
                                step * resources.displayMetrics.density * 120,
                            )
                        }
                    }
                    Command.A,
                    Command.B -> close()
                    else -> {}
                }
        }
    }

    private fun close() {
        work?.cancel()
        overlay = null
        sounds.play(Cue.BACK)
    }

    private fun showMessage(title: String, text: String) {
        overlay = Overlay.Message(title, text)
        sounds.play(Cue.OPEN)
    }

    private fun clearShaderCache() {
        if (!SaveStorage.available()) {
            showMessage("Shader cache", "Storage access required")
            return
        }
        showMessage("Shader cache", "Clearing…")
        lifecycleScope.launch {
            val cleared =
                withContext(Dispatchers.IO) {
                    try {
                        SaveStorage.clearAzaharShaderCache()
                    } catch (error: IOException) {
                        false
                    } catch (error: SecurityException) {
                        false
                    }
                }
            showMessage("Shader cache", if (cleared) "Cleared" else "Could not clear shader cache")
        }
    }

    /** Opens the settings menu, or the scan progress while a scan runs. */
    private fun openSettings() {
        if (library.busy()) return showMessage("Finding your games", library.report())
        overlay = Overlay.Settings()
        sounds.play(Cue.OPEN)
    }

    private fun settingChosen(choice: Int) {
        Core.entries.getOrNull(choice - CORE_SETTINGS_START)?.let {
            overlay = Overlay.Settings(core = it)
            sounds.play(Cue.OPEN)
            return
        }
        if (SETTINGS[choice] == "Game layout") {
            session.setGameRows(if (session.gameRows == 1) 2 else 1)
            sounds.play(Cue.CONFIRM)
            return
        }
        overlay = null
        sounds.play(Cue.CONFIRM)
        when (SETTINGS[choice]) {
            "Choose ROM folder" -> chooseFolder()
            "Rescan folder" ->
                if (library.tree().isEmpty() && !romm.paired) chooseFolder() else rescan()
            "Scan report" -> showMessage("Scan report", library.report())
            "Redownload metadata" -> {
                if (library.busy()) return showMessage("Finding your games", library.report())
                showMessage("Redownloading metadata", "Refreshing metadata and artwork…")
                artwork.clear { removed ->
                    images.evictAll()
                    if (removed) {
                        DroidDeck.clearMetadata(this)
                        this.overlay = null
                        rescan()
                    } else
                        showMessage(
                            "Redownload metadata",
                            "Cached artwork could not be cleared. Try again.",
                        )
                }
            }
            "Remove downloaded art" -> {
                showMessage("Removing downloaded art", "Removing cached covers and screenshots…")
                artwork.clear { removed ->
                    images.evictAll()
                    showMessage(
                        "Downloaded art",
                        if (removed)
                            "Downloaded art removed. Rescan to download it again. Games and saves are kept."
                        else "Some artwork could not be removed. Please try again.",
                    )
                }
            }
            "RomM" ->
                overlay =
                    if (romm.paired) Overlay.RomMPaired(romm.server) else Overlay.RomMAddress()
            "Set as default launcher" -> chooseDefaultLauncher()
            "miel updates" -> checkUpdates()
            "Licenses" ->
                showMessage(
                    "Licenses",
                    assets.open("notices/overview.txt").bufferedReader().use { it.readText() },
                )
        }
    }

    private fun uploadRom(game: Game) {
        if (!romm.paired) {
            overlay = Overlay.RomMAddress()
            return
        }
        session.command(Command.B)
        showMessage("Uploading to RomM", "${game.title} · 0%")
        work = lifecycleScope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    var last = -1
                    romm.upload(game) { progress ->
                        val percent = (progress * 100).toInt()
                        if (percent != last) {
                            last = percent
                            runOnUiThread {
                                if (work?.isActive == true)
                                    overlay =
                                        Overlay.Message(
                                            "Uploading to RomM",
                                            "${game.title} · $percent%",
                                        )
                            }
                        }
                    }
                }
                showMessage("Uploaded to RomM", "Scan the library in RomM, then rescan in miel.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message =
                    if (error is RomM.Refused && error.status == 403)
                        "RomM upload permission is missing. Pair with RomM again."
                    else error.message ?: "The upload failed."
                showMessage("Upload to RomM", message)
            }
        }
    }

    private fun rescan() {
        library.scan(library.tree().takeIf { it.isNotEmpty() }?.let(Uri::parse))
    }

    private fun updateWork(action: String, block: suspend () -> Unit) {
        val previous = work
        previous?.cancel()
        work = lifecycleScope.launch {
            previous?.join()
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                overlay =
                    Overlay.Update(
                        error.message ?: "The update could not finish. Try again.",
                        action,
                    )
                sounds.play(Cue.ERROR)
            }
        }
    }

    private fun checkUpdates() {
        updateRelease = null
        overlay = Overlay.Update("Installed version: ${updates.version}. Checking GitHub…")
        updateWork("Check again") {
            val release = withContext(Dispatchers.IO) { updates.check() }
            updateRelease = release
            overlay =
                if (release == null) {
                    Overlay.Update(
                        "Installed version: ${updates.version}. No newer stable release is available.",
                        "Check again",
                    )
                } else {
                    val size = (release.bytes + 1024 * 1024 - 1) / (1024 * 1024)
                    Overlay.Update(
                        "miel ${release.version} is available ($size MB). Installed version: ${updates.version}.",
                        "Download update",
                    )
                }
        }
    }

    private fun downloadUpdate() {
        val release = updateRelease ?: return checkUpdates()
        overlay = Overlay.Update("Downloading miel ${release.version}… 0%")
        updateWork("Download again") {
            withContext(Dispatchers.IO) {
                updates.download(release) { percent ->
                    withContext(Dispatchers.Main) {
                        overlay = Overlay.Update("Downloading miel ${release.version}… $percent%")
                    }
                }
            }
            overlay =
                Overlay.Update(
                    "miel ${release.version} is ready. Android will ask you to confirm installation.",
                    "Install update",
                )
        }
    }

    private fun installUpdate() {
        overlay = Overlay.Update("Checking the downloaded APK…")
        updateWork("Check again") {
            val uri = withContext(Dispatchers.IO) { updates.installerUri() }
            if (!packageManager.canRequestPackageInstalls()) {
                overlay =
                    Overlay.Update(
                        "Allow miel to install updates in Android Settings, then return here.",
                        "Install update",
                    )
                updatePermission.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } else {
                updateInstaller.launch(
                    Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true),
                )
            }
        }
    }

    /** Pairs with the RomM server at `address`, showing the code to approve, then rescans. */
    private fun pair(address: String) {
        showMessage("RomM", "Contacting $address…")
        work = lifecycleScope.launch {
            try {
                val pairing = withContext(Dispatchers.IO) { romm.pair(address) }
                overlay = Overlay.RomMPairing(pairing.code, pairing.link, qrCode(pairing.link))
                while (true) {
                    delay(pairing.interval * 1000)
                    if (withContext(Dispatchers.IO) { romm.approved(pairing) }) break
                }
                sounds.play(Cue.CONFIRM)
                overlay = Overlay.RomMPaired(romm.server)
                rescan()
            } catch (error: IOException) {
                Log.w("MielRomM", "Pairing failed", error)
                showMessage("RomM", "Pairing did not finish. ${error.message}")
            }
        }
    }

    /**
     * Brings `game`'s in-game save in line with RomM, then starts it: a save changed only on the
     * server is downloaded, one changed only here uploaded, and when both changed the player
     * chooses; see RomM.decide. A game starts at once when RomM is unreachable.
     */
    private fun syncAndStart(game: Game) {
        if (!romm.paired || game.rommId == 0 || game.core == null) return start(game)
        if (!SaveStorage.available()) return start(game)
        showMessage("Syncing your save", "Checking RomM for a newer save of ${game.title}…")
        work = lifecycleScope.launch {
            val found =
                withContext(Dispatchers.IO) {
                    try {
                        val file = syncFile(game) ?: return@withContext null
                        val session = romm.negotiate(mapOf(game.rommId to file))
                        val local = if (file.isFile) RomM.hash(file) else ""
                        val server = session.saves[game.rommId]
                        val decision =
                            RomM.decide(local, server?.hash.orEmpty(), romm.synced(game.rommId))
                        Sync(game, file, session, server, local, decision)
                    } catch (error: IOException) {
                        Log.w("MielRomM", "Save sync unavailable", error)
                        null
                    }
                }
            if (found == null) return@launch startAfterSync(game)
            Log.i("MielRomM", "${game.title}: ${found.decision}")
            if (found.decision == "conflict" && found.server != null) {
                conflict = found
                overlay =
                    Overlay.SaveConflict(
                        game.title,
                        date(found.file.lastModified()),
                        date(
                            OffsetDateTime.parse(found.server.updated).toInstant().toEpochMilli(),
                        ),
                    )
                sounds.play(Cue.OPEN)
            } else {
                resolve(found, found.decision)
            }
        }
    }

    /** A game's save sync: its file, session, the server's newest save, local hash and decision. */
    private class Sync(
        val game: Game,
        val file: File,
        val session: RomM.Session,
        val server: RomM.Save?,
        val local: String,
        val decision: String,
    )

    /**
     * Downloads or uploads the save as `action` says, closes the session and starts the game. After
     * a download the game starts fresh, since its checkpoint holds the replaced save.
     */
    private suspend fun resolve(sync: Sync, action: String) {
        val game = sync.game
        showMessage("Syncing your save", "Syncing ${game.title} with RomM…")
        val pulled =
            withContext(Dispatchers.IO) {
                try {
                    transfer(sync.session, game, sync.file, sync.server, action)
                    romm.complete(sync.session, if (action == "no_op") 0 else 1, 0)
                    action == "download"
                } catch (error: IOException) {
                    Log.w("MielRomM", "Save sync failed", error)
                    false
                }
            }
        startAfterSync(game, fresh = pulled)
    }

    /** Downloads, uploads or confirms `game`'s save, then records what both sides now hold. */
    private fun transfer(
        session: RomM.Session,
        game: Game,
        file: File,
        server: RomM.Save?,
        action: String,
    ) {
        when (action) {
            "download" ->
                server?.let {
                    romm.pull(session, it, file)
                    if (game.core == Core.AZAHAR) AzaharSaves.unpack(titleId(game), file)
                    if (game.core == Core.PPSSPP)
                        PspSaves.unpack(SaveStorage.memoryStick(game.romFilename), file)
                }
            "upload" -> romm.push(session, game.rommId, file, game.core!!.coreName)
        }
        if (file.isFile) romm.synced(game.rommId, RomM.hash(file))
    }

    /** Starts `game` once a sync finishes, unless the player closed it. */
    private fun startAfterSync(game: Game, fresh: Boolean = false) {
        if (overlay !is Overlay.Message) return
        overlay = null
        start(game, fresh || session.confirm)
    }

    /**
     * Uploads the in-game saves of RomM games that changed only here since they last synced.
     * Conflicts wait for the player's choice at Play.
     */
    private fun pushSaves() {
        if (!romm.paired || !SaveStorage.available()) return
        val games = library.games.filter { it.rommId != 0 && it.core != null }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val files =
                    games
                        .mapNotNull { game ->
                            syncFile(game)?.takeIf { it.isFile }?.let { game to it }
                        }
                        .toMap()
                if (files.isEmpty()) return@launch
                val session = romm.negotiate(files.mapKeys { it.key.rommId })
                var pushed = 0
                for ((game, file) in files) {
                    val server = session.saves[game.rommId]
                    val decision =
                        RomM.decide(
                            RomM.hash(file),
                            server?.hash.orEmpty(),
                            romm.synced(game.rommId),
                        )
                    if (decision != "upload") continue
                    transfer(session, game, file, server, decision)
                    pushed++
                    Log.i("MielRomM", "Uploaded the save of ${game.title}")
                }
                romm.complete(session, pushed, 0)
            } catch (error: IOException) {
                Log.w("MielRomM", "Save upload failed", error)
            }
        }
    }

    /**
     * The file `game`'s save syncs as: its core's save file, or for a 3DS game an archive of its
     * Azahar saves (see AzaharSaves), or a PSP savedata archive; null for a 3DS game without a
     * known title ID.
     */
    private fun syncFile(game: Game): File? {
        val core = game.core ?: return null
        if (core == Core.PPSSPP) {
            val archive =
                File(cacheDir, "romm-saves/PSP/${game.romFilename.substringBeforeLast('.')}.zip")
            archive.parentFile?.mkdirs()
            PspSaves.pack(SaveStorage.memoryStick(game.romFilename), archive)
            return archive
        }
        if (core != Core.AZAHAR) return SaveStorage.save(core, game.romFilename)
        val title = titleId(game)
        if (title == 0L) return null
        val archive = File(cacheDir, "romm-saves/${game.romFilename.substringBeforeLast('.')}.zip")
        archive.parentFile?.mkdirs()
        AzaharSaves.pack(title, archive)
        return archive
    }

    /** A 3DS game's title ID, from its ROM's header when it is here, else from RomM; or 0. */
    private fun titleId(game: Game): Long {
        val rom =
            if (game.romUri.startsWith("content://")) Uri.parse(game.romUri)
            else
                SaveStorage.download(game.platform, game.romFilename)
                    .takeIf { it.isFile }
                    ?.let(Uri::fromFile)
        val header =
            try {
                rom?.let { uri ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        ByteArray(0x120).also { DataInputStream(input).readFully(it) }
                    }
                }
            } catch (error: IOException) {
                null
            } catch (error: IllegalArgumentException) {
                null
            }
        return header?.let(AzaharSaves::titleId)?.takeIf { it != 0L }
            ?: game.titleId.toLongOrNull(16)
            ?: 0
    }

    private fun date(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    private fun chooseFolder() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
        if (library.tree().isNotEmpty()) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(library.tree()))
        }
        try {
            folderPicker.launch(intent)
        } catch (error: ActivityNotFoundException) {
            showMessage("Choose ROM folder", "No folder picker is installed on this device.")
        }
    }

    private fun folderChosen(tree: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(
                tree,
                flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            )
            library.scan(tree)
        } catch (error: SecurityException) {
            showMessage(
                "Choose ROM folder",
                "Folder access could not be retained. Please choose the folder again.",
            )
        }
    }

    private fun chooseDefaultLauncher() {
        val roles = getSystemService(RoleManager::class.java)
        val intent =
            if (
                roles.isRoleAvailable(RoleManager.ROLE_HOME) &&
                    !roles.isRoleHeld(RoleManager.ROLE_HOME)
            )
                roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            else Intent(Settings.ACTION_HOME_SETTINGS)
        try {
            homeRole.launch(intent)
        } catch (error: ActivityNotFoundException) {
            showMessage(
                "Default launcher",
                "Open Android Settings > Apps > Default apps > Home app and choose miel.",
            )
        }
    }

    /**
     * Starts `game` in CoreActivity, on the top screen in its own task, and ends the library's task
     * and process: AYN's firmware plays any app with a task on the lower display at the
     * bottom-screen volume, and the core needs the memory. The game reopens the library when it
     * returns.
     */
    private fun start(game: Game, fresh: Boolean = session.confirm) {
        val core = game.core ?: return fail(UNPLAYABLE)
        val error =
            when {
                !File(applicationInfo.nativeLibraryDir, core.library).isFile ->
                    "This game needs the ARM64 build for your handheld."
                !(game.romUri.startsWith("content://") || game.romUri.startsWith(RomM.URI)) ||
                    game.romFilename.isEmpty() ->
                    "Re-scan your ROM folder before launching this game."
                else -> null
            }
        if (error != null) return fail(error)
        if (!SaveStorage.available()) {
            overlay = Overlay.SaveAccess(game)
            sounds.play(Cue.OPEN)
            return
        }
        val intent =
            Intent(this, CoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CoreActivity.EXTRA_CORE, core.name)
                .putExtra(CoreActivity.EXTRA_ROM_URI, game.romUri)
                .putExtra(CoreActivity.EXTRA_FILENAME, game.romFilename)
                .putExtra(CoreActivity.EXTRA_ROM_DIRECTORY, game.romDirectory)
                .putExtra(CoreActivity.EXTRA_PLATFORM, game.platform)
                .putExtra(CoreActivity.EXTRA_ROMM_LINK, romm.link(game.rommId, game.romFilename))
                .putExtra(CoreActivity.EXTRA_ROMM_TOKEN, romm.token)
                .putExtra(CoreActivity.EXTRA_TITLE, game.title)
                .putExtra(CoreActivity.EXTRA_ART, artwork.heroPath(game.id))
                .putExtra(CoreActivity.EXTRA_FRESH, fresh)
                .putExtra(CoreActivity.EXTRA_OPTIONS, session.launchOptions(core))
                .putExtra(
                    CoreActivity.EXTRA_DEBUG_REPORTS,
                    intent.getBooleanExtra(CoreActivity.EXTRA_DEBUG_REPORTS, false),
                )
        session.recordLaunch()
        sounds.play(Cue.CONFIRM)
        ArtworkActivity.close()
        startActivity(
            intent,
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
        )
        finishAndRemoveTask()
        Handler(mainLooper).postDelayed({ Process.killProcess(Process.myPid()) }, 1000)
    }

    /**
     * Opens the selected app on its screen, or on the other one, which it then keeps. An app that
     * already runs moves its task there.
     */
    private fun openApp(other: Boolean) {
        val app = session.app ?: return
        val screen = if (other) session.appScreen.other() else session.appScreen
        val component =
            ComponentName.unflattenFromString(app.id) ?: return fail("Cannot open this app.")
        val intent =
            Intent.makeMainActivity(component)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
        if (packageManager.resolveActivity(intent, 0) == null) {
            return fail("${app.label} is no longer installed.")
        }
        val display =
            if (screen == Screen.TOP) Display.DEFAULT_DISPLAY
            else
                Displays.other(this, Display.DEFAULT_DISPLAY)?.displayId
                    ?: return fail("The bottom screen is unavailable.")
        if (screen == Screen.BOTTOM) ArtworkActivity.yieldToApp()
        try {
            startActivity(
                intent,
                ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle(),
            )
        } catch (error: RuntimeException) {
            Log.w(Displays.TAG, "Opening $component failed", error)
            return fail("Android could not open ${app.label}.")
        }
        session.rememberScreen(screen)
        sounds.play(Cue.CONFIRM)
    }

    private fun openDroidDeck(game: Game) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(game.romUri))
                .setClassName(DroidDeck.PACKAGE, "${DroidDeck.PACKAGE}.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(
                intent,
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        } catch (error: android.content.ActivityNotFoundException) {
            return fail("Install DroidDeck to play this game.")
        } catch (error: RuntimeException) {
            Log.w(Displays.TAG, "Opening DroidDeck failed", error)
            return fail("Android could not open DroidDeck.")
        }
        session.recordLaunch()
        sounds.play(Cue.CONFIRM)
    }

    // Input.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (MediaVolume.key(this, event)) return true
        // The address field takes typing; controller buttons still act.
        if (
            overlay is Overlay.RomMAddress &&
                !KeyEvent.isGamepadButton(event.keyCode) &&
                event.keyCode != KeyEvent.KEYCODE_ESCAPE
        ) {
            return super.dispatchKeyEvent(event)
        }
        val direction = direction(event.keyCode)
        if (direction != null) {
            if (event.action == KeyEvent.ACTION_DOWN) keyDirection = direction
            if (event.action == KeyEvent.ACTION_UP && keyDirection == direction) keyDirection = null
            updateDirection()
            return true
        }
        val command = command(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) command(command)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return super.onGenericMotionEvent(event)
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hat = Math.abs(hatX) > .5f || Math.abs(hatY) > .5f
        val x = if (hat) hatX else event.getAxisValue(MotionEvent.AXIS_X)
        val y = if (hat) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
        axisDirection =
            when {
                maxOf(Math.abs(x), Math.abs(y)) <= .65f -> null
                Math.abs(x) >= Math.abs(y) -> if (x < 0) Command.Left else Command.Right
                else -> if (y < 0) Command.Up else Command.Down
            }
        updateDirection()
        return true
    }

    /** Moves once on a direction change, then accelerates held horizontal library navigation. */
    private fun updateDirection() {
        val direction = keyDirection ?: axisDirection
        if (direction == activeDirection) return
        directionRepeat?.cancel()
        directionRepeat = null
        activeDirection = direction
        direction?.let(::command)
        if (
            (direction == Command.Left || direction == Command.Right) &&
                overlay == null &&
                !session.menu
        ) {
            directionRepeat = lifecycleScope.launch {
                delay(400)
                var repeats = 0
                while (activeDirection == direction && overlay == null && !session.menu) {
                    command(direction)
                    delay(if (++repeats < 8) 140L else 100L)
                }
            }
        }
    }

    companion object {
        /** The size covers and icons are decoded at, enough for the largest tile. */
        private const val TILE_PIXELS = 480

        /** Shown until the next input. */
        private const val UNPLAYABLE = "This game’s platform or file format is not supported."

        private var current: LibraryActivity? = null

        /** Forwards a key from the artwork window to the library. */
        fun artworkKey(event: KeyEvent) = current?.dispatchKeyEvent(event) == true

        fun artworkMotion(event: MotionEvent) = current?.onGenericMotionEvent(event) == true

        fun artworkBack() {
            current?.command(Command.B)
        }

        private fun direction(key: Int): Command? =
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> Command.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> Command.Right
                KeyEvent.KEYCODE_DPAD_UP -> Command.Up
                KeyEvent.KEYCODE_DPAD_DOWN -> Command.Down
                else -> null
            }

        private fun command(key: Int): Command? =
            when (key) {
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_A -> Command.A
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_B,
                KeyEvent.KEYCODE_H -> Command.B
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_X -> Command.X
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_Y -> Command.Y
                KeyEvent.KEYCODE_BUTTON_L1 -> Command.PreviousTab
                KeyEvent.KEYCODE_BUTTON_R1 -> Command.NextTab
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_MENU -> Command.Select
                else -> null
            }
    }
}
