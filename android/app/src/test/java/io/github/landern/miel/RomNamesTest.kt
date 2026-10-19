package io.github.landern.miel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomNamesTest {
    @Test
    fun detectsPlatformsByExtensionAndFolder() {
        assertEquals("Nintendo 3DS", RomNames.platform("Sample Racer 7.3DS", ""))
        assertEquals("Nintendo DS", RomNames.platform("Game.nds", ""))
        assertEquals("Game Boy Advance", RomNames.platform("Game.gba", ""))
        assertEquals("PSP", RomNames.platform("Game.iso", "ROMS/PSP/Racing"))
        assertEquals("GameCube", RomNames.platform("Game.iso", "GameCube"))
        assertEquals("Wii", RomNames.platform("Game.rvz", "wii"))
        assertEquals("Nintendo DS", RomNames.platform("Game (Europe).zip", "ROMS/nds"))
        assertEquals("Game Boy Advance", RomNames.platform("Game.zip", "Game Boy Advance"))
        assertEquals("Game Boy", RomNames.platform("Game.zip", "ROMS/gb"))
        for (name in listOf("game.iso", "game.zip", "game.7z", "game.cia", "game.bin")) {
            assertEquals(name, "", RomNames.platform(name, "Unknown"))
        }
    }

    @Test
    fun titlesDropDumpTagsButKeepEditions() {
        assertEquals(
            "Sample Game - New Chapter - Extended Edition",
            RomNames.title(
                "Sample Game - New Chapter - Extended Edition (Europe) (En,Fr,De,Es,It).3ds",
            ),
        )
        assertEquals(
            "Game (Special Edition)",
            RomNames.title("Game (Special Edition) (USA) [!].gba"),
        )
        assertNotEquals(
            LibretroArt.normalize("Game"),
            LibretroArt.normalize("Game (Special Edition)"),
        )
    }

    @Test
    fun regionComesFromATag() {
        assertEquals("USA", RomNames.region("Game (USA).3ds"))
        assertEquals("", RomNames.region("Game.3ds"))
    }

    @Test
    fun idsAreStablePerDocument() {
        assertEquals(RomNames.id("content://test/1"), RomNames.id("content://test/1"))
        assertNotEquals(RomNames.id("content://test/1"), RomNames.id("content://test/2"))
    }

    @Test
    fun everyPlatformHasALibretroSystem() {
        for (platform in
            listOf(
                "Nintendo 3DS",
                "Nintendo DS",
                "Game Boy Advance",
                "Game Boy",
                "Game Boy Color",
                "NES",
                "SNES",
                "Nintendo 64",
                "GameCube",
                "Wii",
                "PSP",
                "PlayStation",
            )) {
            assertTrue(platform, RomNames.system(platform).isNotEmpty())
        }
    }
}
