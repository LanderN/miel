package io.github.landern.miel

import io.github.landern.miel.HowLongToBeat.Result
import io.github.landern.miel.HowLongToBeat.Times
import org.junit.Assert.assertEquals
import org.junit.Test

class HowLongToBeatTest {
    @Test
    fun platformBeatsASlightlyBetterNameElsewhere() {
        val results =
            listOf(
                result("Sample Quest", platforms = "PC"),
                result("Sample Quest 3D", "Nintendo 3DS"),
            )
        assertEquals(1, HowLongToBeat.best("Sample Quest", "Nintendo 3DS", "", results))
    }

    @Test
    fun matchingIgnoresAccentsAndPunctuation() {
        val results =
            listOf(
                result("Another Story"),
                result("Other Name", alias = "Cafe Delice - Salt and Pepper"),
            )
        assertEquals(1, HowLongToBeat.best("Café Délice: Salt & Pepper!", "GameCube", "", results))
    }

    @Test
    fun weakMatchesAreDropped() {
        assertEquals(
            -1,
            HowLongToBeat.best(
                "Sample Quest",
                "Nintendo DS",
                "",
                listOf(result("Different Story")),
            ),
        )
    }

    @Test
    fun numberedTitlesPreferNumberedResults() {
        val results = listOf(result("Sample Racer"), result("Sample Racer 2 DX"))
        assertEquals(1, HowLongToBeat.best("Sample Racer 2", "Nintendo DS", "", results))
    }

    @Test
    fun steamIdBeatsTheName() {
        val results =
            listOf(
                result("Sample Quest"),
                result("Sample Quest Remastered").copy(steam = 4242),
            )
        assertEquals(1, HowLongToBeat.best("Sample Quest", "PC Games", "4242", results))
    }

    @Test
    fun popularityBreaksTies() {
        val results =
            listOf(
                result("Sample Quest").copy(completions = 3),
                result("Sample Quest").copy(completions = 90),
            )
        assertEquals(1, HowLongToBeat.best("Sample Quest", "Nintendo DS", "", results))
    }

    @Test
    fun describeRoundsToHalfHoursAndSkipsMissingTimes() {
        assertEquals(
            "Main 12½h · 100% 35h",
            HowLongToBeat.describe(Times(12 * 3600 + 1700, 0, 35 * 3600 - 800)),
        )
        assertEquals("Main 45m · Extras 1h", HowLongToBeat.describe(Times(2700, 3700, 0)))
        assertEquals("", HowLongToBeat.describe(Times(0, 0, 0)))
    }

    private fun result(name: String, platforms: String = "", alias: String = "") =
        Result(name, alias, platforms.split(", "), Times(3600, 7200, 10800))
}
