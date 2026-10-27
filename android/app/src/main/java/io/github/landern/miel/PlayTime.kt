package io.github.landern.miel

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Each game's total play time and when it was last played, by game ID. CoreActivity adds to it in
 * the game process; the library reads it after the game returns, in a new process.
 */
object PlayTime {
    private const val FILE = "play-time"

    fun add(context: Context, id: String, millis: Long) {
        if (id.isEmpty() || millis <= 0) return
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        preferences
            .edit()
            .putLong(id, preferences.getLong(id, 0) + millis)
            .putLong("$id.last", System.currentTimeMillis())
            .apply()
    }

    fun played(context: Context, id: String): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(id, 0)

    /** "3 h 12 min played · Yesterday", or an empty string for a game never played. */
    fun describe(context: Context, id: String): String {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return describe(preferences.getLong(id, 0), preferences.getLong("$id.last", 0))
    }

    internal fun describe(
        played: Long,
        last: Long,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (played <= 0 || last <= 0) return ""
        val day = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
        val date =
            when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day)
            }
        return "${duration(played)} played · $date"
    }

    /** "3 h 12 min", "12 min", or "1 min" for anything shorter. */
    fun duration(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(1)
        val hours = minutes / 60
        return if (hours > 0) "$hours h ${minutes % 60} min" else "$minutes min"
    }
}
