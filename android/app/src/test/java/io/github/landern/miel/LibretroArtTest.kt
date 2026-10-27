package io.github.landern.miel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibretroArtTest {
    @Test
    fun listingsDecodeLinksAndKeepLiteralPlus() {
        val html =
            "<a href=\"A+B%20(Europe).png\">cover</a><a href=\"../bad.png\">bad</a>" +
                "<a href=\"%ZZ.png\">bad</a><a HREF=\"Another%20Game.png\">art</a>"
        assertEquals(listOf("A+B (Europe).png", "Another Game.png"), LibretroArt.filenames(html))
    }

    @Test
    fun urlsEncodeEachSegment() {
        assertTrue(
            LibretroArt.imageUrl(THREE_DS, LibretroArt.BOXARTS, "A+B (Europe).png")
                .endsWith("Nintendo%20-%20Nintendo%203DS/Named_Boxarts/A%2BB%20%28Europe%29.png"),
        )
        assertTrue(
            LibretroArt.imageUrl(THREE_DS, LibretroArt.SNAPS, "Sample Game (USA).png")
                .contains("/Named_Snaps/Sample%20Game"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LibretroArt.listingUrl("3DS", "../invalid")
        }
    }

    @Test
    fun matchesNeedTheTitleAndRegion() {
        val files =
            listOf(
                "Sample Racer 7 (USA).png",
                "Sample Racer 7 (Europe) (Rev 1).png",
                "Sample Racer 7 (Europe).png",
                "Sample Racer 8 (Europe).png",
            )
        assertEquals(
            "Sample Racer 7 (Europe).png",
            LibretroArt.match(files, "Sample Racer 7", "Europe"),
        )
        assertNull("no other region", LibretroArt.match(files, "Sample Racer 7", "Japan"))
        assertNull("no other sequel", LibretroArt.match(files, "Sample Racer", "Europe"))
        assertNull(
            "no dropped edition",
            LibretroArt.match(
                listOf("Sample Game - New Chapter (Europe).png"),
                "Sample Game: New Chapter – Extended edition",
                "Europe",
            ),
        )
    }

    @Test
    fun normalizingIgnoresArticlesPunctuationAndAccents() {
        assertEquals(
            LibretroArt.normalize("The Sample Quest: Another Chapter"),
            LibretroArt.normalize("Sample Quest, The - Another Chapter (Europe).png"),
        )
        assertEquals(
            LibretroArt.normalize("Café Adventure"),
            LibretroArt.normalize("Cafe Adventure (Europe).png"),
        )
    }

    private companion object {
        const val THREE_DS = "Nintendo - Nintendo 3DS"
    }
}
