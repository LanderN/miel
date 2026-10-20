package io.github.landern.miel

import java.io.InputStream
import java.util.Locale

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
