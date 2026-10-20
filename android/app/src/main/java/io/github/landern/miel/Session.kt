package io.github.landern.miel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.json.JSONObject

/** The screen an app opens on. */
enum class Screen(val label: String) {
    TOP("Top screen"),
    BOTTOM("Bottom screen");

    fun other() = if (this == TOP) BOTTOM else TOP
}

/** Library tabs, in shoulder-button order. */
enum class Tab(val label: String, val platform: String? = null) {
    RECENT("Recent"),
    N3DS("3DS", "Nintendo 3DS"),
    NDS("NDS", "Nintendo DS"),
    GBA("GBA", "Game Boy Advance"),
    GBC("GBC", "Game Boy Color"),
    GB("GB", "Game Boy"),
    NES("NES", "NES"),
    SNES("SNES", "SNES"),
    PSX("PSX", "PlayStation"),
    PSP("PSP", "PSP"),
    PC("PC", DroidDeck.PLATFORM),
    APPS("Apps"),
}

/** Rows of the apps grid, filled column by column. */
const val APP_ROWS = 3

/** Most columns on a page of the games and apps grids. */
const val GAME_COLUMNS = 4
const val APP_COLUMNS = 7

/** Controller input and touches on the library. */
sealed interface Command {
    object Left : Command

    object Right : Command

    object Up : Command

    object Down : Command

    /** L: the previous tab; in More, a lower resolution. */
    object PreviousTab : Command

    /** R: the next tab; in More, a higher resolution. */
    object NextTab : Command

    object A : Command

    /** B, Back, Escape and the touch Cancel button. */
    object B : Command

    object X : Command

    object Y : Command

    /** A library tab was touched. */
    data class ChooseTab(val tab: Tab) : Command

    /** Select or the settings gear. */
    object Select : Command

    /** A game or app tile was touched; an index into the tab's list. */
    data class Pick(val index: Int) : Command

    /** A game layout segment in Settings was touched. */
    data class GameRows(val value: Int) : Command

    /** A resolution segment in More was touched. */
    data class Resolution(val value: Int) : Command

    /** The library lost the foreground. */
    object Suspend : Command
}

/** Azahar options for one game, changed in More. */
data class CoreSettings(val resolution: Int = 3, val hardwareShaders: Boolean = true)

val RESOLUTIONS = 1..4

/** The persisted part of the session, keyed by game ID and app ID. */
data class Disk(
    /** Launch counters; the highest is the most recent game. */
    val recent: Map<String, Long> = emptyMap(),
    val settings: Map<String, CoreSettings> = emptyMap(),
    val screens: Map<String, Screen> = emptyMap(),
    val gameRows: Int = 2,
    val coreOptions: Map<String, Map<String, String>> = emptyMap(),
    val gameOptions: Map<String, Map<String, String>> = emptyMap(),
) {
    fun toJson(): String =
        JSONObject()
            .put("version", VERSION)
            .put(
                "journeys",
                JSONObject(recent.mapValues { JSONObject().put("recent", it.value) }),
            )
            .put(
                "settings",
                JSONObject(
                    settings.mapValues {
                        JSONObject()
                            .put("resolution", it.value.resolution)
                            .put("hardware_shaders", it.value.hardwareShaders)
                    },
                ),
            )
            .put("screens", JSONObject(screens.mapValues { it.value.name.lowercase() }))
            .put("core_options", JSONObject(coreOptions.mapValues { JSONObject(it.value) }))
            .put("game_options", JSONObject(gameOptions.mapValues { JSONObject(it.value) }))
            .put("game_rows", gameRows)
            .toString()

    companion object {
        private const val VERSION = 1

        fun parse(text: String): Disk? =
            try {
                val json = JSONObject(text)
                if (json.getInt("version") != VERSION) null
                else
                    Disk(
                        recent =
                            json.getJSONObject("journeys").entries {
                                getJSONObject(it).optLong("recent")
                            },
                        settings =
                            json.optJSONObject("settings").entries {
                                val settings = getJSONObject(it)
                                CoreSettings(
                                    settings.getInt("resolution"),
                                    settings.getBoolean("hardware_shaders"),
                                )
                            },
                        screens =
                            json.optJSONObject("screens").entries {
                                Screen.valueOf(getString(it).uppercase())
                            },
                        coreOptions =
                            json.optJSONObject("core_options").entries {
                                getJSONObject(it).entries { getString(it) }
                            },
                        gameOptions =
                            json.optJSONObject("game_options").entries {
                                getJSONObject(it).entries { getString(it) }
                            },
                        gameRows = json.optInt("game_rows", 2).takeIf { it in 1..2 } ?: 2,
                    )
            } catch (error: Exception) {
                null
            }

        private fun <T> JSONObject?.entries(value: JSONObject.(String) -> T): Map<String, T> =
            this?.keys()?.asSequence()?.associateWith { value(it) }.orEmpty()
    }
}

/**
 * The library's state: its games and apps, the selection, the More dialog and each game's saved
 * settings and launch order. Opens the session file at `path`, falling back to its backup.
 */
class Session(private val path: File) {
    var games by mutableStateOf(emptyList<Game>())
        private set

    /** Sorted by label. */
    var apps by mutableStateOf(emptyList<InstalledApp>())
        private set

    var disk by mutableStateOf(Disk())
        private set

    var tab by mutableStateOf(Tab.RECENT)
        private set

    /** Index into `games`. */
    var selected by mutableStateOf(0)
        private set

    /** Index into `apps`. */
    var appSelected by mutableStateOf(0)
        private set

    /** More is open. */
    var menu by mutableStateOf(false)
        private set

    /** More asks to confirm Start fresh. */
    var confirm by mutableStateOf(false)
        private set

    var error by mutableStateOf("")

    init {
        val primary = read(path)
        if (primary == null && path.exists()) {
            error = "Recovered your launch history after an unreadable session file."
        }
        disk = primary ?: read(backup(path)) ?: Disk()
    }

    /** Recent and Apps stay visible; platform tabs need at least one ROM. */
    val tabs: List<Tab>
        get() =
            Tab.entries.filter { tab ->
                tab.platform == null || games.any { it.platform == tab.platform }
            }

    /** Indices into the full library for the active tab. */
    val order: List<Int>
        get() =
            when (tab) {
                Tab.RECENT ->
                    games.indices
                        .filter { games[it].id in disk.recent }
                        .sortedByDescending { disk.recent[games[it].id] }
                        .take(10)
                Tab.APPS -> emptyList()
                else -> games.indices.filter { games[it].platform == tab.platform }
            }

    val game: Game?
        get() = games.getOrNull(selected)?.takeIf { selected in order }

    val app: InstalledApp?
        get() = apps.getOrNull(appSelected)

    /** The selection's position in `order`. */
    val position: Int
        get() = order.indexOf(selected).coerceAtLeast(0)

    val launched: Boolean
        get() = game?.id in disk.recent

    val settings: CoreSettings
        get() {
            if (game?.core != Core.AZAHAR) return CoreSettings()
            val options = optionValues(Core.AZAHAR, perGame = true)
            return CoreSettings(
                options.getValue("citra_resolution_factor").toInt(),
                options["citra_use_hw_shader"] == "enabled",
            )
        }

    fun optionValues(core: Core, perGame: Boolean = false): Map<String, String> {
        val saved =
            disk.coreOptions[core.name].orEmpty() + if (perGame) gameOverrides() else emptyMap()
        return core.settingOptions.associate { it.key to it.value(saved[it.key]) }
    }

    fun gameOverrides(): Map<String, String> {
        val id = game?.id ?: return emptyMap()
        return disk.settings[id]?.options().orEmpty() + disk.gameOptions[id].orEmpty()
    }

    fun changeOption(core: Core, index: Int, step: Int, perGame: Boolean) {
        val option = core.settingOptions.getOrNull(index) ?: return
        val choices = if (perGame) listOf(null) + option.values else option.values
        val current = if (perGame) gameOverrides()[option.key] else optionValues(core)[option.key]
        val value =
            choices[Math.floorMod(choices.indexOf(current).coerceAtLeast(0) + step, choices.size)]
        if (perGame) {
            val id = game?.takeIf { it.core == core }?.id ?: return
            val options =
                gameOverrides().toMutableMap().apply {
                    if (value == null) remove(option.key) else put(option.key, value)
                }
            changeDisk {
                it.copy(settings = it.settings - id, gameOptions = it.gameOptions + (id to options))
            }
        } else {
            val options =
                disk.coreOptions[core.name].orEmpty() + (option.key to requireNotNull(value))
            changeDisk { it.copy(coreOptions = it.coreOptions + (core.name to options)) }
        }
    }

    fun launchOptions(core: Core): Array<String> {
        val fixed = core.options(CoreSettings()).toList().chunked(2).associate { it[0] to it[1] }
        return (fixed + optionValues(core, perGame = true))
            .flatMap { listOf(it.key, it.value) }
            .toTypedArray()
    }

    /** The screen the selected app opens on. */
    val appScreen: Screen
        get() = app?.let { disk.screens[it.id] } ?: Screen.TOP

    /**
     * The page of `columns` game columns holding the selection, plus the next column, which peeks
     * in at the right edge. Returns the selection's slot on the page and the page's game indices.
     */
    fun page(columns: Int): Pair<Int, List<Int>> {
        val order = order
        val (slot, range) = page(position, order.size, gameRows, columns)
        return slot to order.slice(range)
    }

    /** Columns per page of the games grid: as many as the games fill, from two to four. */
    val gameColumns: Int
        get() = ((order.size + gameRows - 1) / gameRows).coerceIn(2, GAME_COLUMNS)

    val gameRows: Int
        get() = disk.gameRows

    fun setGameRows(rows: Int) {
        if (rows in 1..2 && rows != gameRows) changeDisk { it.copy(gameRows = rows) }
    }

    /** Like `page`, for the apps: the selection's slot and the page's app indices. */
    fun appPage(columns: Int): Pair<Int, IntRange> = page(appSelected, apps.size, APP_ROWS, columns)

    /** Replaces the library, keeping the selected game where it still exists. */
    fun replaceGames(games: List<Game>) {
        val id = game?.id
        closeMenu()
        this.games = games
        if (tab !in tabs) tab = Tab.RECENT
        val order = order
        selected =
            games.indexOfFirst { it.id == id }.takeIf { it in order } ?: order.firstOrNull() ?: 0
    }

    /** Replaces the apps, keeping the selected app where it still exists. */
    fun replaceApps(apps: List<InstalledApp>) {
        val id = app?.id
        this.apps = apps
        appSelected =
            (apps.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: appSelected)
                .coerceAtMost(apps.lastIndex)
                .coerceAtLeast(0)
    }

    /** Makes `screen` the one the selected app opens on, once Android opened it there. */
    fun rememberScreen(screen: Screen) {
        val app = app ?: return
        if (appScreen != screen) changeDisk { it.copy(screens = it.screens + (app.id to screen)) }
    }

    /** Closes More and records the selected game in launch order. */
    fun recordLaunch() {
        closeMenu()
        val game = game ?: return
        disk =
            disk.copy(recent = disk.recent + (game.id to (disk.recent.values.maxOrNull() ?: 0) + 1))
        checkpoint()
    }

    private fun chooseTab(tab: Tab) {
        if (tab !in tabs) return
        this.tab = tab
        if (tab != Tab.APPS && selected !in order) selected = order.firstOrNull() ?: 0
    }

    fun command(command: Command) {
        if (!menu) {
            when (command) {
                Command.PreviousTab,
                Command.NextTab -> {
                    val tabs = tabs
                    val step = if (command == Command.PreviousTab) -1 else 1
                    chooseTab(tabs[Math.floorMod(tabs.indexOf(tab) + step, tabs.size)])
                    return
                }
                is Command.ChooseTab -> {
                    chooseTab(command.tab)
                    return
                }
                else -> {}
            }
        }
        if (tab == Tab.APPS) {
            when (command) {
                in MOVES -> appSelected = moved(appSelected, apps.size, APP_ROWS, command)
                is Command.Pick -> if (command.index in apps.indices) appSelected = command.index
                Command.B -> chooseTab(Tab.RECENT)
                else -> {}
            }
            return
        }
        if (order.isEmpty()) return
        val inMore = menu && !confirm
        val adjusting = inMore && game?.core == Core.AZAHAR
        when {
            !menu && command in MOVES -> {
                val order = order
                selected = order[moved(position, order.size, gameRows, command)]
            }
            adjusting && command in STEPS ->
                changeSettings {
                    val step =
                        if (command == Command.Left || command == Command.PreviousTab) -1 else 1
                    it.copy(
                        resolution =
                            (it.resolution + step).coerceIn(RESOLUTIONS.first, RESOLUTIONS.last),
                    )
                }
            adjusting && command is Command.Resolution && command.value in RESOLUTIONS ->
                changeSettings { it.copy(resolution = command.value) }
            adjusting && command == Command.Y ->
                changeSettings { it.copy(hardwareShaders = !it.hardwareShaders) }
            !menu && command is Command.Pick && command.index in order -> selected = command.index
            command == Command.X && game?.let(DroidDeck::isGame) != true -> {
                menu = !menu
                confirm = false
            }
            inMore && command == Command.A -> confirm = true
            menu && command == Command.B -> closeMenu()
            command == Command.Suspend -> {
                checkpoint()
                closeMenu()
            }
        }
    }

    /** Writes the session file. On failure, sets `error` and keeps the in-memory state. */
    fun checkpoint(): Boolean =
        try {
            persist(path, disk)
            error = ""
            true
        } catch (failure: IOException) {
            error = "Couldn’t save your settings. Please try again."
            false
        }

    /** Applies `change` to the selected game's settings, undone if they cannot be saved. */
    private fun changeSettings(change: (CoreSettings) -> CoreSettings) {
        val id = game?.id ?: return
        val settings = change(settings)
        changeDisk {
            it.copy(
                settings = it.settings + (id to settings),
                gameOptions =
                    it.gameOptions +
                        (id to (it.gameOptions[id].orEmpty() - settings.options().keys)),
            )
        }
    }

    /** Applies `change` to the persisted state, undone if it cannot be saved. */
    private fun changeDisk(change: (Disk) -> Disk) {
        val previous = disk
        disk = change(disk)
        if (!checkpoint()) disk = previous
    }

    private fun closeMenu() {
        menu = false
        confirm = false
    }

    private companion object {
        val STEPS = setOf(Command.Left, Command.Right, Command.PreviousTab, Command.NextTab)
        val MOVES = setOf(Command.Left, Command.Right, Command.Up, Command.Down)

        fun backup(path: File) = File(path.path.removeSuffix(".json") + ".bak")

        fun read(path: File): Disk? =
            try {
                Disk.parse(path.readText())
            } catch (error: IOException) {
                null
            }

        /**
         * Writes `disk` to `path` through a synced temporary file. A previous file that still
         * parses is first copied to `.bak`, so a damaged file never replaces a good backup.
         */
        fun persist(path: File, disk: Disk) {
            val parent = path.absoluteFile.parentFile
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create $parent")
            val temporary = File(parent, path.name + ".tmp")
            writeSynced(temporary, disk.toJson())
            val previous = if (path.exists()) path.readText() else null
            if (previous != null && Disk.parse(previous) != null) {
                val staged = File(parent, path.name + ".bak.tmp")
                writeSynced(staged, previous)
                rename(staged, backup(path))
            }
            rename(temporary, path)
            FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }

        fun writeSynced(file: File, text: String) {
            FileOutputStream(file).use {
                it.write(text.toByteArray())
                it.fd.sync()
            }
        }

        fun rename(from: File, to: File) {
            if (!from.renameTo(to)) throw IOException("Cannot replace $to")
        }
    }
}

/**
 * Moves `position` by `command` in a grid of `size` items that fills `rows` per column. Right from
 * a column next to a shorter last column goes to the last item.
 */
internal fun moved(position: Int, size: Int, rows: Int, command: Command): Int {
    val last = (size - 1).coerceAtLeast(0)
    return when {
        command == Command.Left && position >= rows -> position - rows
        command == Command.Right && position + rows <= last -> position + rows
        command == Command.Right && position / rows < last / rows -> last
        command == Command.Up && position % rows > 0 -> position - 1
        command == Command.Down && position % rows + 1 < rows && position < last -> position + 1
        else -> position
    }
}

/**
 * The page of `columns` columns holding `position` in a grid of `size` items that fills `rows` per
 * column, plus the next column. Returns `position`'s slot on the page and the page's positions.
 */
internal fun page(position: Int, size: Int, rows: Int, columns: Int): Pair<Int, IntRange> {
    val pageSize = rows * columns.coerceAtLeast(1)
    val start = position / pageSize * pageSize
    return position - start to (start until minOf(start + pageSize + rows, size))
}
