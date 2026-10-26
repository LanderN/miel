package io.github.landern.miel

/** Supplemental context for the selected upper-screen setting. */
data class SettingsDetail(
    val context: String,
    val title: String,
    val value: String? = null,
    val description: String,
)

internal fun settingsDetail(overlay: Overlay.Settings, session: Session): SettingsDetail {
    val core = overlay.core
    if (core != null) {
        val context =
            if (overlay.perGame) "${core.coreName} · ${session.game?.title.orEmpty()}"
            else "${core.coreName} · Defaults for all games"
        val option =
            core.settingOptions.getOrNull(overlay.selected)
                ?: return SettingsDetail(
                    context,
                    "Clear shader cache",
                    description = "Removes Azahar’s compiled and transferable shader caches.",
                )
        val current = option.label(session.optionValues(core, overlay.perGame).getValue(option.key))
        val inherited = overlay.perGame && option.key !in session.gameOverrides()
        return SettingsDetail(
            context,
            option.label,
            if (inherited) "Default: $current" else current,
            if (inherited)
                "Follows the emulator’s default for this game. Changes apply on next launch."
            else if (overlay.perGame) "Applies to this game. Changes apply on next launch."
            else "Default for games without an override. Changes apply on next launch.",
        )
    }
    val title = SETTINGS.getOrNull(overlay.selected).orEmpty()
    val emulator = Core.entries.getOrNull(overlay.selected - CORE_SETTINGS_START)
    val context =
        when {
            overlay.selected < CORE_SETTINGS_START -> "Library"
            emulator != null -> "Emulators"
            else -> "Device & storage"
        }
    val description =
        when (title) {
            "Game layout" -> "Choose one or two rows of games in the library."
            "Choose ROM folder" -> "Choose the folder miel scans for games."
            "Rescan folder" -> "Scan the ROM folder and paired RomM server for games."
            "Scan report" -> "Show the results of the library scan."
            "RomM" -> "Pair with a RomM server to browse its games and sync in-game saves."
            "Redownload metadata" -> "Clear cached artwork and metadata lookups, then rescan."
            "Remove downloaded art" ->
                "Remove cached covers and screenshots. Local artwork stays in place."
            "Set as default launcher" -> "Choose miel as Android’s Home app."
            "miel updates" -> "Check GitHub for a newer stable release of miel."
            "Licenses" -> "Read the included software license notices."
            else ->
                emulator
                    ?.let { "Set emulator defaults for ${it.platforms.joinToString(" / ")}." }
                    .orEmpty()
        }
    return SettingsDetail(
        context,
        title,
        if (overlay.selected == 0)
            "${session.gameRows} ${if (session.gameRows == 1) "row" else "rows"}"
        else null,
        description,
    )
}
