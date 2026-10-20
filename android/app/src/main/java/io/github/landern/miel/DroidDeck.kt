package io.github.landern.miel

import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/** DroidDeck's exported game files and canonical launch links. */
object DroidDeck {
    const val PLATFORM = "PC Games"
    const val PACKAGE = "com.droiddeck.launcher"
    private const val PREFIX = "droiddeck://game/"

    fun readId(stream: InputStream): String? {
        val bytes = ByteArray(129)
        var size = 0
        while (size < bytes.size) {
            val count = stream.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count == 0) return null
            size += count
        }
        if (size > 128) return null
        return String(bytes, 0, size, Charsets.UTF_8).trim().takeIf(::validId)
    }

    private fun validId(value: String): Boolean =
        value.isNotEmpty() &&
            value[0] in '1'..'9' &&
            value.all { it in '0'..'9' } &&
            value.toULongOrNull() != null

    fun game(id: String, filename: String): Game =
        Game(
            id = "droiddeck-$id",
            title = filename.substringBeforeLast('.'),
            platform = PLATFORM,
            region = "",
            system = "",
            romUri = PREFIX + id,
            romFilename = filename,
        )

    fun isGame(game: Game): Boolean =
        game.platform == PLATFORM &&
            game.romUri.startsWith(PREFIX) &&
            validId(game.romUri.removePrefix(PREFIX))

    fun artwork(game: Game, icon: String): Game {
        if (!isGame(game)) return game
        val id = game.romUri.removePrefix(PREFIX)
        if (id.toUIntOrNull() == null) return game.copy(cover = icon)
        val assets = "https://cdn.akamai.steamstatic.com/steam/apps/$id"
        return game.copy(
            cover = "$assets/library_600x900_2x.jpg",
            screenshot = "$assets/library_hero.jpg",
        )
    }

    fun metadata(context: Context, game: Game): Game {
        if (!isGame(game)) return game
        val id = game.romUri.removePrefix(PREFIX)
        if (id.toUIntOrNull() == null) return game
        val cache = context.getSharedPreferences("steam-metadata", Context.MODE_PRIVATE)
        val data =
            try {
                val cached = cache.getString(id, null)
                if (cached != null) JSONObject(cached)
                else {
                    val response =
                        JSONObject(
                                String(
                                    fetchBytes(
                                        "https://store.steampowered.com/api/appdetails?appids=$id&l=english",
                                        1024 * 1024,
                                    ),
                                    Charsets.UTF_8,
                                ),
                            )
                            .optJSONObject(id)
                    val details =
                        if (response?.optBoolean("success") == true)
                            response.optJSONObject("data") ?: JSONObject()
                        else JSONObject()
                    val fields =
                        JSONObject()
                            .put(
                                "date",
                                details.optJSONObject("release_date")?.optString("date").orEmpty(),
                            )
                            .put(
                                "genre",
                                details
                                    .optJSONArray("genres")
                                    ?.optJSONObject(0)
                                    ?.optString("description")
                                    .orEmpty(),
                            )
                    cache.edit().putString(id, fields.toString()).apply()
                    fields
                }
            } catch (error: Exception) {
                if (error !is IOException && error !is JSONException) throw error
                if (Thread.currentThread().isInterrupted) throw error
                Log.w("MielLibrary", "Cannot load Steam metadata for $id", error)
                cache.edit().putString(id, "{}").apply()
                return game
            }
        val year = Regex("\\b[0-9]{4}\\b").find(data.optString("date"))?.value?.toIntOrNull()
        return game.copy(
            year = year?.takeIf { it in 1..9999 } ?: game.year,
            genre = data.optString("genre").ifBlank { game.genre },
        )
    }

    fun clearMetadata(context: Context) {
        context.getSharedPreferences("steam-metadata", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The artwork stem produced by DroidDeck's export. */
    fun iconStem(filename: String): String =
        filename
            .substringBeforeLast('.')
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .lowercase(Locale.ROOT)
            .take(50)
            .trimEnd('-')
}
