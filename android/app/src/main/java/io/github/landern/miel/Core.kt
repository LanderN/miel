package io.github.landern.miel

/** An embedded libretro core: which games it runs and how the host shows them. */
enum class Core(
    val platforms: Set<String>,
    val library: String,
    /** The core's library_name, which the host checks. */
    val coreName: String,
    /** The core's library_version. Checkpoints are kept per version. */
    val version: String,
    /** The ROM file extensions the core loads. */
    val extensions: Set<String>,
    /**
     * Whether the frame stacks the upper screen over a lower touchscreen; otherwise it is one
     * screen, shown on the upper display.
     */
    val dual: Boolean,
    /** The lower screen's side margins in the frame, as a fraction of its width. */
    val lowerInset: Float,
) {
    AZAHAR(
        setOf("Nintendo 3DS"),
        "libazahar_libretro.so",
        "Azahar",
        "2126.2",
        setOf("3ds", "3dsx", "z3dsx", "cci", "zcci", "cxi", "zcxi", "app", "elf", "axf"),
        dual = true,
        lowerInset = .1f,
    ),
    MELONDS(
        setOf("Nintendo DS"),
        "libmelondsds_libretro.so",
        "melonDS DS",
        "1.4.0",
        setOf("nds"),
        dual = true,
        lowerInset = 0f,
    ),
    MGBA(
        setOf("Game Boy Advance", "Game Boy", "Game Boy Color"),
        "libmgba_libretro.so",
        "mGBA",
        "0.10.5",
        setOf("gba", "gb", "gbc"),
        dual = false,
        lowerInset = 0f,
    ),
    FCEUMM(
        setOf("NES"),
        "libfceumm_libretro.so",
        "FCEUmm",
        "(SVN)",
        setOf("nes", "unf", "unif"),
        dual = false,
        lowerInset = 0f,
    ),
    BSNES(
        setOf("SNES"),
        "libbsnes_libretro.so",
        "bsnes",
        "115",
        setOf("sfc", "smc"),
        dual = false,
        lowerInset = 0f,
    ),
    PCSX(
        setOf("PlayStation"),
        "libpcsx_rearmed_libretro.so",
        "PCSX-ReARMed",
        "r26 c8816799",
        setOf("chd", "pbp", "iso", "exe", "bin", "cue", "m3u"),
        dual = false,
        lowerInset = 0f,
    ),
    PPSSPP(
        setOf("PSP"),
        "libppsspp_libretro.so",
        "PPSSPP",
        "1.20.4",
        setOf("iso", "cso", "pbp", "chd", "elf", "prx"),
        dual = false,
        lowerInset = 0f,
    );

    fun accepts(filename: String): Boolean =
        filename.substringAfterLast('.', "").lowercase().let { it in extensions || it == "zip" }

    /** The core options for a game, as key and value pairs; the rest keep the core's defaults. */
    fun options(settings: CoreSettings): Array<String> =
        when (this) {
            AZAHAR ->
                arrayOf(
                    "citra_graphics_api",
                    "OpenGL",
                    "citra_use_hw_shader",
                    if (settings.hardwareShaders) "enabled" else "disabled",
                    "citra_texture_filter",
                    "none",
                    "citra_texture_sampling",
                    "GameControlled",
                    "citra_resolution_factor",
                    settings.resolution.toString(),
                    "citra_layout_option",
                    "default",
                    "citra_swap_screen",
                    "Top",
                    "citra_render_3d",
                    "off",
                    "citra_enable_touch_touchscreen",
                    "enabled",
                    "citra_use_libretro_save_path",
                    "LibRetro Default",
                )
            MELONDS ->
                arrayOf(
                    "melonds_number_of_screen_layouts",
                    "1",
                    "melonds_screen_layout1",
                    "top-bottom",
                    "melonds_screen_gap",
                    "0",
                    "melonds_show_cursor",
                    "disabled",
                    "melonds_touch_mode",
                    "touch",
                    "melonds_show_current_layout",
                    "disabled",
                )
            MGBA,
            FCEUMM,
            BSNES -> emptyArray()
            PPSSPP -> arrayOf("ppsspp_backend", "opengl", "ppsspp_internal_resolution", "960x544")
            PCSX -> arrayOf("pcsx_rearmed_memcard1", "libretro", "pcsx_rearmed_memcard2", "none")
        }

    companion object {
        fun of(platform: String): Core? = entries.firstOrNull { platform in it.platforms }
    }
}
