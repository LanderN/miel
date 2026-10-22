package io.github.landern.miel

/** A user-facing subset of a core's libretro options. */
data class CoreOption(
    val key: String,
    val label: String,
    val values: List<String>,
    val labels: List<String> = values,
    val default: String = values.first(),
) {
    fun value(saved: String?): String = saved?.takeIf { it in values } ?: default

    fun label(value: String): String = labels[values.indexOf(value).coerceAtLeast(0)]
}

private fun toggle(key: String, label: String, enabled: Boolean = false) =
    CoreOption(
        key,
        label,
        listOf("disabled", "enabled"),
        listOf("Off", "On"),
        if (enabled) "enabled" else "disabled",
    )

val Core.settingOptions: List<CoreOption>
    get() =
        when (this) {
            Core.AZAHAR ->
                listOf(
                    CoreOption(
                        "citra_resolution_factor",
                        "Resolution",
                        (1..4).map(Int::toString),
                        (1..4).map { "$it×" },
                        "3",
                    ),
                    toggle("citra_use_hw_shader", "Hardware shaders", true),
                    CoreOption(
                        "citra_graphics_api",
                        "Graphics API",
                        listOf("OpenGL", "Vulkan"),
                    ),
                    toggle("citra_async_shader_compilation", "Async shader compilation"),
                    toggle("citra_spirv_shader_gen", "SPIR-V shader generation", true),
                    CoreOption(
                        "citra_disable_spirv_optimizer",
                        "SPIR-V optimization",
                        listOf("enabled", "disabled"),
                        listOf("Off", "On"),
                    ),
                    CoreOption(
                        "citra_language_value",
                        "System language",
                        listOf(
                            "English",
                            "Japanese",
                            "French",
                            "Spanish",
                            "German",
                            "Italian",
                            "Dutch",
                            "Portuguese",
                            "Russian",
                            "Korean",
                            "Traditional Chinese",
                            "Simplified Chinese",
                        ),
                    ),
                    CoreOption(
                        "citra_texture_filter",
                        "Texture filter",
                        listOf(
                            "none",
                            "Anime4K Ultrafast",
                            "Bicubic",
                            "ScaleForce",
                            "xBRZ",
                            "MMPX",
                        ),
                        listOf("Off", "Anime4K Ultrafast", "Bicubic", "ScaleForce", "xBRZ", "MMPX"),
                    ),
                    CoreOption(
                        "citra_texture_sampling",
                        "Texture sampling",
                        listOf("GameControlled", "NearestNeighbor", "Linear"),
                        listOf("Game-controlled", "Nearest", "Linear"),
                    ),
                    CoreOption(
                        "citra_cpu_clock_percentage",
                        "Emulated CPU clock",
                        listOf("50", "75", "100", "125", "150", "175", "200"),
                        listOf("50%", "75%", "100%", "125%", "150%", "175%", "200%"),
                        "100",
                    ),
                )
            Core.MELONDS ->
                listOf(
                    CoreOption(
                        "melonds_audio_interpolation",
                        "Audio interpolation",
                        listOf("disabled", "linear", "cosine", "cubic", "gaussian"),
                        listOf("Off", "Linear", "Cosine", "Cubic", "Gaussian"),
                    ),
                    CoreOption(
                        "melonds_audio_bitdepth",
                        "Audio bit depth",
                        listOf("auto", "10bit", "16bit"),
                        listOf("Automatic", "10-bit", "16-bit"),
                    ),
                    CoreOption(
                        "melonds_firmware_language",
                        "System language",
                        listOf("auto", "en", "ja", "fr", "de", "it", "es", "default"),
                        listOf(
                            "Automatic",
                            "English",
                            "Japanese",
                            "French",
                            "German",
                            "Italian",
                            "Spanish",
                            "Firmware default",
                        ),
                    ),
                    CoreOption(
                        "melonds_mic_input",
                        "Simulated microphone",
                        listOf("microphone", "blow", "noise"),
                        listOf("Off", "Blow", "Noise"),
                    ),
                    CoreOption(
                        "melonds_mic_input_active",
                        "Microphone activation",
                        listOf("hold", "toggle", "always"),
                        listOf("Hold L3", "Toggle with L3", "Always"),
                    ),
                )
            Core.MGBA ->
                listOf(
                    CoreOption(
                        "mgba_gb_model",
                        "Game Boy model",
                        listOf(
                            "Autodetect",
                            "Game Boy",
                            "Super Game Boy",
                            "Game Boy Color",
                            "Game Boy Advance",
                        ),
                    ),
                    CoreOption(
                        "mgba_gb_colors",
                        "Game Boy palette",
                        listOf("Grayscale", "DMG Green", "GB Pocket", "GB Light"),
                    ),
                    CoreOption(
                        "mgba_sgb_borders",
                        "Super Game Boy borders",
                        listOf("ON", "OFF"),
                        listOf("On", "Off"),
                    ),
                    CoreOption(
                        "mgba_solar_sensor_level",
                        "Solar sensor level",
                        (0..10).map(Int::toString),
                    ),
                    CoreOption(
                        "mgba_force_gbp",
                        "Game Boy Player rumble",
                        listOf("OFF", "ON"),
                        listOf("Off", "On"),
                    ),
                    toggle("mgba_audio_low_pass_filter", "Audio filter"),
                    CoreOption(
                        "mgba_use_bios",
                        "Use BIOS if available",
                        listOf("ON", "OFF"),
                        listOf("On", "Off"),
                    ),
                    CoreOption(
                        "mgba_skip_bios",
                        "Skip BIOS intro",
                        listOf("OFF", "ON"),
                        listOf("Off", "On"),
                    ),
                )
            Core.FCEUMM ->
                listOf(
                    CoreOption("fceumm_region", "Region", listOf("Auto", "NTSC", "PAL", "Dendy")),
                    toggle("fceumm_nospritelimit", "Remove sprite limit"),
                    CoreOption(
                        "fceumm_palette",
                        "Colour palette",
                        listOf(
                            "default",
                            "rgb",
                            "pal",
                            "sony-cxa2025as-us",
                            "nes-classic-fbx-fs",
                            "wavebeam",
                        ),
                        listOf(
                            "Default",
                            "Nintendo RGB",
                            "PAL",
                            "Sony CXA2025AS",
                            "NES Classic",
                            "Wavebeam",
                        ),
                    ),
                    CoreOption(
                        "fceumm_overscan_h_left",
                        "Crop left edge",
                        (0..16 step 4).map(Int::toString),
                    ),
                    CoreOption(
                        "fceumm_overscan_h_right",
                        "Crop right edge",
                        (0..16 step 4).map(Int::toString),
                    ),
                    CoreOption(
                        "fceumm_overscan_v_top",
                        "Crop top edge",
                        (0..24 step 4).map(Int::toString),
                        default = "8",
                    ),
                    CoreOption(
                        "fceumm_overscan_v_bottom",
                        "Crop bottom edge",
                        (0..24 step 4).map(Int::toString),
                        default = "8",
                    ),
                )
            Core.BSNES ->
                listOf(
                    CoreOption(
                        "bsnes_ppu_fast",
                        "Fast PPU",
                        listOf("ON", "OFF"),
                        listOf("On", "Off"),
                    ),
                    CoreOption(
                        "bsnes_ppu_overscan_v",
                        "Crop overscan",
                        listOf("0", "8", "12", "16"),
                        listOf("Off", "8 pixels", "12 pixels", "16 pixels"),
                        default = "8",
                    ),
                    CoreOption(
                        "bsnes_ppu_no_sprite_limit",
                        "Remove sprite limit (hack)",
                        listOf("OFF", "ON"),
                        listOf("Off", "On"),
                    ),
                    CoreOption(
                        "bsnes_blur_emulation",
                        "Blur emulation",
                        listOf("OFF", "ON"),
                        listOf("Off", "On"),
                    ),
                    CoreOption(
                        "bsnes_cpu_sfx_overclock",
                        "Super FX clock",
                        listOf("50", "70", "100", "150", "200"),
                        listOf("50%", "70%", "100%", "150%", "200%"),
                        default = "100",
                    ),
                )
            Core.PCSX ->
                listOf(
                    CoreOption(
                        "pcsx_rearmed_region",
                        "Region",
                        listOf("auto", "NTSC", "PAL"),
                        listOf("Automatic", "NTSC", "PAL"),
                    ),
                    CoreOption(
                        "pcsx_rearmed_spu_interpolation",
                        "Audio interpolation",
                        listOf("simple", "gaussian", "cubic", "off"),
                        listOf("Simple", "Gaussian", "Cubic", "Off"),
                    ),
                    toggle("pcsx_rearmed_show_bios_bootlogo", "BIOS boot logo"),
                    toggle("pcsx_rearmed_neon_enhancement_enable", "Enhanced resolution"),
                    CoreOption(
                        "pcsx_rearmed_dithering",
                        "Dithering",
                        listOf("enabled", "disabled", "force"),
                        listOf("On", "Off", "Force"),
                    ),
                )
            Core.PPSSPP ->
                listOf(
                    CoreOption(
                        "ppsspp_language",
                        "System language",
                        listOf(
                            "Automatic",
                            "English",
                            "Japanese",
                            "French",
                            "Spanish",
                            "German",
                            "Italian",
                            "Dutch",
                            "Portuguese",
                            "Russian",
                            "Korean",
                            "Chinese Traditional",
                            "Chinese Simplified",
                        ),
                    ),
                    CoreOption(
                        "ppsspp_frameskip",
                        "Frameskip",
                        listOf("disabled", "1", "2", "3", "4"),
                        listOf("Off", "1", "2", "3", "4"),
                    ),
                    toggle("ppsspp_auto_frameskip", "Automatic frameskip"),
                    CoreOption(
                        "ppsspp_texture_anisotropic_filtering",
                        "Anisotropic filtering",
                        listOf("disabled", "2x", "4x", "8x", "16x"),
                        listOf("Off", "2×", "4×", "8×", "16×"),
                        "16x",
                    ),
                    CoreOption(
                        "ppsspp_texture_scaling_level",
                        "Texture upscaling",
                        listOf("disabled", "2x", "3x", "4x"),
                        listOf("Off", "2×", "3×", "4×"),
                    ),
                    CoreOption(
                        "ppsspp_internal_resolution",
                        "Resolution",
                        listOf("480x272", "960x544", "1440x816", "1920x1088"),
                        (1..4).map { "$it×" },
                        "960x544",
                    ),
                    CoreOption(
                        "ppsspp_texture_filtering",
                        "Texture filtering",
                        listOf("Auto", "Nearest", "Linear"),
                        listOf("Automatic", "Nearest", "Linear"),
                    ),
                )
        }

fun CoreSettings.options(): Map<String, String> =
    mapOf(
        "citra_resolution_factor" to resolution.toString(),
        "citra_use_hw_shader" to if (hardwareShaders) "enabled" else "disabled",
    )

/** Settings rows, including actions that apply to the whole core. */
fun Core.settingIndices(perGame: Boolean): IntRange =
    0 until settingOptions.size + if (this == Core.AZAHAR && !perGame) 1 else 0
