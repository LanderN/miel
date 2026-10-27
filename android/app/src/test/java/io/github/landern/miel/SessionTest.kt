package io.github.landern.miel

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val dir = Files.createTempDirectory("miel-session").toFile()
    private val file = File(dir, "sessions-v1.json")

    @After fun removeDir() = dir.deleteRecursively().let {}

    private fun game(n: Int, platform: String = "Nintendo 3DS") =
        Game("rom-$n", "Game $n", platform, "", "", "content://test/$n", "game-$n.3ds")

    private fun open(count: Int = 4, path: File = file) =
        Session(path).apply {
            replaceGames((0 until count).map(::game))
            command(Command.ChooseTab(Tab.N3DS))
        }

    private fun Session.run(vararg commands: Command) = commands.forEach(::command)

    private fun apps(vararg ids: String) = ids.map { InstalledApp(it, it.uppercase()) }

    @Test
    fun launchesOrderTheLibraryAndSurviveReopening() {
        val s = open()
        s.run(Command.Down)
        s.recordLaunch()
        s.run(Command.Pick(2))
        s.recordLaunch()
        s.run(Command.ChooseTab(Tab.RECENT))
        assertEquals(listOf(2, 1), s.order)

        val reopened = Session(file).apply { replaceGames(s.games) }
        assertEquals("reopening selects the most recent game", 2, reopened.selected)
        assertTrue(reopened.launched)
        reopened.run(Command.Down)
        assertEquals(1, reopened.selected)
    }

    @Test
    fun recentKeepsTheTenLatestGames() {
        val s = open(12)
        repeat(12) {
            s.run(Command.Pick(it))
            s.recordLaunch()
        }
        s.run(Command.ChooseTab(Tab.RECENT))
        assertEquals((11 downTo 2).toList(), s.order)
        s.run(Command.ChooseTab(Tab.N3DS), Command.Pick(0))
        s.recordLaunch()
        s.run(Command.ChooseTab(Tab.RECENT))
        assertEquals(listOf(0) + (11 downTo 3).toList(), s.order)
        s.run(Command.ChooseTab(Tab.N3DS))
        assertEquals(12, s.order.size)
    }

    @Test
    fun anUnreadableFileFallsBackToTheBackup() {
        val s = open()
        s.recordLaunch()
        s.run(Command.Down)
        s.recordLaunch()
        file.writeText("broken")
        val recovered = Session(file).apply { replaceGames(s.games) }
        assertEquals("the backup holds the previous launch", listOf(0), recovered.order)
        assertTrue(recovered.error.isNotEmpty())
    }

    @Test
    fun startFreshNeedsConfirmation() {
        val s = open()
        s.run(Command.X, Command.A)
        assertTrue(s.menu && s.confirm)
        s.run(Command.X, Command.X)
        assertTrue("reopening More asks again", s.menu && !s.confirm)
        s.run(Command.A, Command.B)
        assertFalse("B cancels", s.menu || s.confirm)
        s.run(Command.X, Command.A)
        s.recordLaunch()
        assertFalse("a launch closes More", s.menu || s.confirm)
    }

    @Test
    fun favoritesFormATabThatPersistsAndDisappearsWhenEmpty() {
        val s = open()
        assertFalse(Tab.FAVORITES in s.tabs)
        s.run(Command.Pick(2), Command.X)
        assertEquals(Command.Favorite, s.moreActions.first())
        s.run(Command.Favorite)
        assertFalse("adding closes More", s.menu)
        s.run(Command.Pick(0), Command.X, Command.Favorite)
        assertEquals(listOf(Tab.RECENT, Tab.FAVORITES, Tab.N3DS, Tab.APPS), s.tabs)

        val reopened = open()
        reopened.run(Command.ChooseTab(Tab.FAVORITES))
        assertEquals("library order", listOf(0, 2), reopened.order)
        reopened.run(Command.X, Command.Favorite)
        assertEquals(listOf(2), reopened.order)
        assertEquals("the selection moves to a remaining favorite", 2, reopened.selected)
        reopened.run(Command.X, Command.Favorite)
        assertEquals("an emptied tab returns to Recent", Tab.RECENT, reopened.tab)
        assertFalse(Tab.FAVORITES in reopened.tabs)
    }

    @Test
    fun fastForwardSettingsCycleAndPersist() {
        val s = open()
        assertEquals(
            mapOf("button" to "off", "mode" to "hold", "speed" to "3", "sound" to "on"),
            s.fastForward,
        )
        s.changeFastForward(0, -1)
        s.changeFastForward(2, 1)
        assertEquals("r3", open().fastForward["button"])
        assertEquals("4", open().fastForward["speed"])
    }

    @Test
    fun failedWritesUndoTheChange() {
        File(dir, "blocker").writeText("blocked")
        val s = open(path = File(dir, "blocker/sessions-v1.json"))
        s.run(Command.X, Command.Favorite)
        assertTrue(s.disk.favorites.isEmpty())
        assertTrue(s.error.isNotEmpty())
        s.setGameRows(1)
        assertEquals("a failed write keeps the layout", 2, s.gameRows)
    }

    @Test
    fun libraryPagesAndKeepsTheSelection() {
        val s = open(7)
        s.run(Command.Right, Command.Right, Command.Down, Command.Down)
        assertEquals("the grid fills down each column of two", 5, s.selected)
        assertEquals(5 to listOf(0, 1, 2, 3, 4, 5, 6), s.page(3))
        assertEquals(1 to listOf(4, 5, 6), s.page(2))
        s.run(Command.Right)
        assertEquals("Right reaches the shorter last column", 6, s.selected)
        s.run(Command.Left, Command.Up, Command.Up, Command.Pick(9))
        assertEquals(4, s.selected)
        s.run(Command.Pick(4))
        s.run(Command.Down)

        s.replaceGames(s.games.reversed())
        assertEquals("rom-5", s.game?.id)
    }

    @Test
    fun gameLayoutChangesNavigationAndPersists() {
        val s = open(8)
        assertEquals(2, s.gameRows)
        s.run(Command.Pick(4))
        s.setGameRows(1)
        assertEquals("changing layout keeps the selected game", 4, s.selected)
        assertEquals(0 to listOf(4, 5, 6, 7), s.page(s.gameColumns))
        s.run(Command.Up, Command.Down)
        assertEquals(4, s.selected)
        s.run(Command.Right)
        assertEquals(5, s.selected)
        assertEquals(1, open().gameRows)
        s.setGameRows(0)
        assertEquals(1, s.gameRows)
        s.setGameRows(2)
        assertEquals(5, s.selected)
        assertEquals(5 to (0..7).toList(), s.page(s.gameColumns))
        assertEquals(2, open().gameRows)
    }

    @Test
    fun platformTabsFilterTheGridAndShouldersWrapPastEmptyPlatforms() {
        val s = Session(file)
        val platforms =
            listOf("Game Boy", "Nintendo 3DS", "Game Boy Advance", "Game Boy Color", "Nintendo DS")
        s.replaceGames(platforms.mapIndexed { i, platform -> game(i, platform) })
        assertEquals(
            listOf("Recent", "3DS", "NDS", "GBA", "GBC", "GB", "Apps"),
            s.tabs.map { it.label },
        )
        assertTrue(s.order.isEmpty())
        assertEquals(null, s.game)
        s.run(Command.A, Command.Right, Command.X)
        assertFalse(s.menu)
        s.run(Command.NextTab)
        assertEquals(Tab.N3DS, s.tab)
        assertEquals(listOf(1), s.order)
        s.run(Command.Pick(0))
        assertEquals("a hidden ROM cannot be selected", 1, s.selected)
        s.run(Command.NextTab)
        assertEquals(Tab.NDS, s.tab)
        assertEquals(listOf(4), s.order)
        s.run(Command.ChooseTab(Tab.GB))
        assertEquals(listOf(0), s.page(s.gameColumns).second)
        s.run(Command.NextTab, Command.NextTab)
        assertEquals(Tab.RECENT, s.tab)
        s.run(Command.PreviousTab)
        assertEquals(Tab.APPS, s.tab)

        s.replaceGames(listOf(game(1)))
        assertEquals(listOf(Tab.RECENT, Tab.N3DS, Tab.APPS), s.tabs)
        s.run(Command.PreviousTab)
        assertEquals(Tab.N3DS, s.tab)
        s.replaceGames(emptyList())
        assertEquals(Tab.RECENT, s.tab)
        assertEquals(listOf(Tab.RECENT, Tab.APPS), s.tabs)
        assertEquals(null, s.game)
    }

    @Test
    fun platformSelectionStaysInItsGridAcrossRescans() {
        val s = open()
        s.run(Command.Pick(2))
        s.replaceGames(listOf(game(9, "Game Boy")) + s.games.reversed())
        assertEquals("rom-2", s.game?.id)
        assertEquals(listOf(1, 2, 3, 4), s.order)
        s.replaceGames(listOf(game(9, "Game Boy"), game(3)))
        assertEquals("rom-3", s.game?.id)
        s.run(Command.X, Command.PreviousTab)
        assertEquals("shoulders in More keep the tab", Tab.N3DS, s.tab)
    }

    @Test
    fun olderSessionsDefaultToTwoRows() {
        val json = org.json.JSONObject(Disk().toJson())
        json.remove("game_rows")
        assertEquals(2, Disk.parse(json.toString())?.gameRows)
        json.put("game_rows", 9)
        assertEquals(2, Disk.parse(json.toString())?.gameRows)
    }

    @Test
    fun anEmptyLibraryIsSafe() {
        val s = open(0)
        s.run(Command.Left, Command.Right, Command.A, Command.X, Command.B, Command.Pick(0))
        assertFalse(s.menu)
        assertEquals(0 to emptyList<Int>(), s.page(3))
        s.setGameRows(1)
        assertEquals(1, s.gameRows)
        assertEquals(0 to emptyList<Int>(), s.page(3))
        s.replaceGames(listOf(game(0, platform = "Nintendo 64")))
        assertFalse(s.games.single().playable)
        s.run(Command.NextTab)
        assertEquals("the apps do not need games", Tab.APPS, s.tab)
        s.run(Command.Down, Command.Right)
        assertEquals(0, s.appSelected)
        assertEquals(0 to (0 until 0), s.appPage(5))
    }

    @Test
    fun appsKeepTheirSelectionAndScreen() {
        val s = open()
        s.replaceApps(apps("a", "b", "c", "d", "e"))
        s.run(Command.NextTab, Command.Right, Command.Down, Command.Down)
        assertEquals(Tab.APPS to 4, s.tab to s.appSelected)
        assertEquals("the games keep their selection", 0, s.selected)
        assertEquals(1 to (3 until 5), s.appPage(1))
        assertEquals(Screen.TOP, s.appScreen)
        s.rememberScreen(Screen.BOTTOM)

        s.replaceApps(apps("b", "e"))
        assertEquals(1, s.appSelected)
        assertEquals(Screen.BOTTOM, s.appScreen)
        s.replaceApps(apps("b"))
        assertEquals("a removed app moves the selection to the last one", 0, s.appSelected)
        assertEquals(Screen.TOP, s.appScreen)
        s.run(Command.B, Command.PreviousTab, Command.Pick(3), Command.NextTab)
        assertEquals(Tab.RECENT to 0, s.tab to s.appSelected)

        val reopened = open()
        reopened.replaceApps(apps("e"))
        assertEquals(Screen.BOTTOM, reopened.appScreen)
    }
}
