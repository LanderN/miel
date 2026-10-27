package io.github.landern.miel

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTimeTest {
    private val today = LocalDate.of(2026, 10, 27)

    private fun at(day: LocalDate) = day.atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun describe(played: Long, last: Long) =
        PlayTime.describe(played, last, today, ZoneOffset.UTC)

    @Test
    fun describesDurationAndDay() {
        assertEquals("", describe(0, at(today)))
        assertEquals("1 min played · Today", describe(20_000, at(today)))
        assertEquals("59 min played · Yesterday", describe(59 * 60_000L, at(today.minusDays(1))))
        assertEquals("3 h 12 min", PlayTime.duration((3 * 60 + 12) * 60_000L))
    }
}
