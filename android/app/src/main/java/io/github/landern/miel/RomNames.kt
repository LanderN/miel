package io.github.landern.miel

import java.security.MessageDigest

/** What a ROM's file name says about it. The file itself is never opened. */
object RomNames {
    private val DUMP_TAGS =
        Regex(
            "(?i)\\s*\\((?:Europe|USA|Japan|World|Australia|Korea|Taiwan|China|Brazil|Canada" +
                "|France|Germany|Spain|Italy|UK|Rev[^)]*|v\\d[^)]*" +
                "|En(?:,[A-Za-z]{2})*|Ja(?:,[A-Za-z]{2})*|Fr(?:,[A-Za-z]{2})*)\\)",
        )
    private val BRACKETS = Regex("\\s*\\[[^]]*\\]")
    private val REGIONS = listOf("Europe", "USA", "Japan", "World", "Australia", "Korea", "Taiwan")

    /**
     * The platform for `filename`, or an empty string. Disc images and ZIP archives need a platform
     * name among the folders in `folder`, the path from the chosen root.
     */
    fun platform(filename: String, folder: String): String =
        when (val ext = filename.substringAfterLast('.').lowercase()) {
            "3ds",
            "3dsx",
            "z3dsx",
            "cci",
            "zcci",
            "cxi",
            "zcxi" -> "Nintendo 3DS"
            "steam" -> DroidDeck.PLATFORM
            "nds" -> "Nintendo DS"
            "gba" -> "Game Boy Advance"
            "gb" -> "Game Boy"
            "gbc" -> "Game Boy Color"
            "nes",
            "unf",
            "unif" -> "NES"
            "sfc",
            "smc",
            "swc",
            "fig" -> "SNES"
            "n64",
            "z64",
            "v64" -> "Nintendo 64"
            "gcm" -> "GameCube"
            "cso" -> "PSP"
            "zip" -> {
                val path = path(folder)
                when {
                    inFolder(path, "3ds", "nintendo 3ds") -> "Nintendo 3DS"
                    inFolder(path, "nds", "ds", "nintendo ds") -> "Nintendo DS"
                    inFolder(path, "gba", "game boy advance") -> "Game Boy Advance"
                    inFolder(path, "gbc", "game boy color") -> "Game Boy Color"
                    inFolder(path, "gb", "game boy") -> "Game Boy"
                    inFolder(path, "psp", "playstation portable", "sony playstation portable") ->
                        "PSP"
                    inFolder(path, "ps1", "psx", "playstation", "sony playstation") -> "PlayStation"
                    inFolder(path, "gc", "ngc", "gamecube", "nintendo gamecube") -> "GameCube"
                    inFolder(path, "nes", "famicom", "nintendo entertainment system") -> "NES"
                    inFolder(
                        path,
                        "snes",
                        "sfc",
                        "super nintendo",
                        "super nintendo entertainment system",
                    ) -> "SNES"
                    else -> ""
                }
            }
            "iso",
            "rvz",
            "gcz",
            "wia",
            "ciso",
            "dol",
            "chd",
            "pbp",
            "exe",
            "bin",
            "cue",
            "elf",
            "prx",
            "m3u" -> {
                val path = path(folder)
                val iso = ext == "iso"
                val rvz = ext == "rvz"
                when {
                    ext in setOf("iso", "pbp", "chd", "elf", "prx") &&
                        inFolder(
                            path,
                            "psp",
                            "playstation portable",
                            "sony playstation portable",
                        ) -> "PSP"
                    ext in setOf("iso", "rvz", "gcz", "wia", "ciso", "dol", "elf") &&
                        inFolder(path, "gc", "ngc", "gamecube", "nintendo gamecube") -> "GameCube"
                    (iso || rvz) && inFolder(path, "wii", "nintendo wii") -> "Wii"
                    ext in setOf("iso", "chd", "pbp", "exe", "bin", "cue", "m3u") &&
                        inFolder(path, "ps1", "psx", "playstation", "sony playstation") ->
                        "PlayStation"
                    else -> ""
                }
            }
            else -> ""
        }

    private fun path(folder: String) = "/" + folder.lowercase().replace('\\', '/') + "/"

    private fun inFolder(path: String, vararg names: String): Boolean = names.any {
        "/$it/" in path
    }

    /** Libretro's name for `platform`, or an empty string. */
    fun system(platform: String): String =
        when (platform) {
            "Nintendo 3DS" -> "Nintendo - Nintendo 3DS"
            "Nintendo DS" -> "Nintendo - Nintendo DS"
            "Game Boy Advance" -> "Nintendo - Game Boy Advance"
            "Game Boy" -> "Nintendo - Game Boy"
            "Game Boy Color" -> "Nintendo - Game Boy Color"
            "NES" -> "Nintendo - Nintendo Entertainment System"
            "SNES" -> "Nintendo - Super Nintendo Entertainment System"
            "Nintendo 64" -> "Nintendo - Nintendo 64"
            "GameCube" -> "Nintendo - GameCube"
            "Wii" -> "Nintendo - Wii"
            "PSP" -> "Sony - PlayStation Portable"
            "PlayStation" -> "Sony - PlayStation"
            else -> ""
        }

    /** The file name without its extension and dump tags. Edition names in brackets stay. */
    fun title(filename: String): String {
        val dot = filename.lastIndexOf('.')
        val title =
            (if (dot > 0) filename.substring(0, dot) else filename)
                .replace(DUMP_TAGS, "")
                .replace(BRACKETS, "")
                .replace('_', ' ')
                .trim()
        return title.ifEmpty { filename }
    }

    /** The region a tag in `filename` names, or an empty string. */
    fun region(filename: String): String {
        val lower = filename.lowercase()
        return REGIONS.firstOrNull { "(${it.lowercase()})" in lower } ?: ""
    }

    /** "rom-" and the SHA-256 of `key`. */
    fun id(key: String): String =
        "rom-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).toHex()
}

/** The bytes as lowercase hexadecimal. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
