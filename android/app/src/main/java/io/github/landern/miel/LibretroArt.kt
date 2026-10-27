package io.github.landern.miel

import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Libretro's thumbnail naming: directory listings per system and kind, and exact title matching
 * against their file names.
 */
object LibretroArt {
    const val BOXARTS = "Named_Boxarts"
    const val SNAPS = "Named_Snaps"
    private val LINK = Pattern.compile("href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
    private val MARKS = Regex("\\p{M}")
    private val PNG = Regex("\\.png$")
    private val TAGS =
        Regex(
            "\\s*\\((?:europe|usa|japan|world|australia|korea|taiwan|china|brazil|canada" +
                "|france|germany|spain|italy|uk|rev[^)]*|v\\d[^)]*" +
                "|[a-z]{2}(?:,[a-z]{2})*)\\)",
        )
    private val BRACKETS = Regex("\\s*\\[[^]]*\\]")
    private val LEADING_THE = Regex("^the\\s+")
    private val TRAILING_THE = Regex(",\\s*the\\b")
    private val PUNCTUATION = Regex("[^a-z0-9]")

    fun listingUrl(system: String, kind: String): String {
        require(kind == BOXARTS || kind == SNAPS) { "Unsupported artwork kind" }
        return "https://thumbnails.libretro.com/${encode(system)}/$kind/"
    }

    fun imageUrl(system: String, kind: String, file: String): String =
        listingUrl(system, kind) + encode(file)

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** The PNG files a directory listing links to. */
    fun filenames(html: String): List<String> {
        val links = LINK.matcher(html)
        return buildList {
            while (links.find()) {
                val file =
                    try {
                        // Listings contain literal + signs as well as encoded spaces.
                        URLDecoder.decode(links.group(1)!!.replace("+", "%2B"), "UTF-8")
                    } catch (malformed: IllegalArgumentException) {
                        continue
                    }
                if (file.endsWith(".png") && '/' !in file && '\\' !in file) add(file)
            }
        }
    }

    /**
     * A title or file name reduced to lowercase letters and digits, without accents, region and
     * revision tags or a leading or trailing "The". Edition names and sequel numbers stay.
     */
    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()
            .replaceFirst(PNG, "")
            .replace(TAGS, "")
            .replace(BRACKETS, "")
            .replaceFirst(LEADING_THE, "")
            .replace(TRAILING_THE, "")
            .replace(PUNCTUATION, "")

    /** The file for `title` in `region`, preferring the unrevised release, or null. */
    fun match(files: List<String>, title: String, region: String): String? {
        val wanted = normalize(title)
        return files
            .filter { normalize(it) == wanted && "($region)" in it }
            .minWithOrNull(compareBy<String>({ "(Rev " in it }, { it }))
    }
}
