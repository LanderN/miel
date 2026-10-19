package io.github.landern.miel

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** Optional metadata and artwork beside a ROM, applied after RomM linking. */
class LocalMetadata(
    private val metadata: JSONObject,
    private val cover: String,
    private val screenshot: String,
) {
    fun apply(game: Game): Game =
        game.copy(
            title = metadata.optString("title").ifBlank { game.title },
            region = metadata.optString("region").ifBlank { game.region },
            year = metadata.optInt("year").takeIf { it in 1..9999 } ?: game.year,
            genre = metadata.optString("genre").ifBlank { game.genre },
            cover = cover.ifEmpty { game.cover },
            screenshot = screenshot.ifEmpty { game.screenshot },
        )

    companion object {
        private const val LIMIT = 64 * 1024

        fun read(context: Context, game: Game, siblings: Map<String, String>): LocalMetadata {
            val basename = game.romFilename.substringBeforeLast('.')
            val metadata =
                siblings["$basename.json"]?.let { source ->
                    try {
                        val bytes =
                            context.contentResolver.openInputStream(Uri.parse(source))?.use {
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = it.read(buffer)
                                    if (count == -1) break
                                    if (output.size() + count > LIMIT)
                                        throw IOException("Metadata exceeds 64 KiB")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: throw IOException("Cannot open metadata")
                        JSONObject(String(bytes, Charsets.UTF_8))
                    } catch (error: Exception) {
                        if (
                            error !is IOException &&
                                error !is JSONException &&
                                error !is SecurityException
                        )
                            throw error
                        Log.w(
                            "MielLibrary",
                            "Cannot read local metadata for ${game.romFilename}",
                            error,
                        )
                        null
                    }
                } ?: JSONObject()
            return LocalMetadata(
                metadata,
                siblings["$basename.cover.png"].orEmpty(),
                siblings["$basename.screenshot.png"].orEmpty(),
            )
        }
    }
}
