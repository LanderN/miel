package io.github.landern.miel

enum class Emulator {
    BUILT_IN,
    POKEDAISY;

    fun label(game: Game): String =
        when (this) {
            BUILT_IN -> requireNotNull(game.core).displayName
            POKEDAISY -> "PokeDaisy"
        }

    fun supports(game: Game): Boolean =
        when (this) {
            BUILT_IN -> game.core != null
            POKEDAISY -> sharesPokeDaisySaves(game.platform) && game.core?.accepts(game.romFilename) == true
        }

    companion object {
        const val POKEDAISY_PACKAGE = "com.pokedaisy.app"

        fun choices(game: Game): List<Emulator> = entries.filter { it.supports(game) }

        fun sharesPokeDaisySaves(platform: String): Boolean =
            platform in Core.MGBA.platforms || platform in Core.MELONDS.platforms
    }
}

class RomFileProvider : androidx.core.content.FileProvider()
