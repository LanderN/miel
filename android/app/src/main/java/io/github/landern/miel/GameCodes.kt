package io.github.landern.miel

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Per-disc Gecko and unencrypted Action Replay codes. */
object GameCodes {
    enum class Format(val section: String, val label: String) {
        GECKO("Gecko", "Gecko"),
        ACTION_REPLAY("ActionReplay", "Action Replay"),
    }

    data class Code(
        val name: String,
        val body: String,
        val format: Format = Format.GECKO,
        val enabled: Boolean = false,
        val notes: String = "",
        val widescreen: Boolean = false,
    )

    private val line = Regex("[0-9A-Fa-f]{8}\\s+[0-9A-Fa-f]{8}")
    private const val MARKER = "# Managed by miel game codes\n"

    fun code(name: String, body: String, format: Format): Code {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 160 && title.none { it in "\r\n" }) {
            "Enter a single-line code name, up to 160 characters"
        }
        val rows = body.trim().lines().map(String::trim).filter(String::isNotEmpty)
        require(rows.isNotEmpty() && rows.all(line::matches)) {
            "Use two groups of eight hexadecimal digits per line"
        }
        require(rows.size <= 4096) { "Code is too long" }
        return Code(
            title,
            rows.joinToString("\n") { it.uppercase().replace(Regex("\\s+"), " ") },
            format,
        )
    }

    fun catalog(text: String, disc: GameCubeDisc): List<Code> {
        val entries = JSONArray(text)
        return (0 until entries.length())
            .map(entries::getJSONObject)
            .filter { entry ->
                entry.getString("disc_id") == disc.id &&
                    entry.getJSONArray("revisions").let { revisions ->
                        (0 until revisions.length()).any { revisions.getInt(it) == disc.revision }
                    }
            }
            .map { entry ->
                val body = entry.getJSONArray("body")
                code(
                        entry.getString("name"),
                        (0 until body.length()).joinToString("\n") { body.getString(it) },
                        Format.valueOf(entry.getString("format")),
                    )
                    .copy(
                        widescreen = true,
                        notes =
                            listOf(
                                    entry.getString("author"),
                                    entry.optString("notes"),
                                    "Source: ${entry.getString("source")}",
                                    if (entry.optBoolean("tested_in_miel")) ""
                                    else "Not tested in miel",
                                )
                                .filter(String::isNotBlank)
                                .joinToString("\n"),
                    )
            }
    }

    fun merge(saved: List<Code>, catalog: List<Code>): List<Code> =
        saved +
            catalog.filter { incoming ->
                saved.none { it.format == incoming.format && it.body == incoming.body }
            }

    private fun file(root: File, disc: GameCubeDisc) =
        File(root, "${disc.id}r${disc.revision}.json")

    fun load(root: File, disc: GameCubeDisc): List<Code> {
        val file = file(root, disc)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            code(
                    item.getString("name"),
                    item.getString("body"),
                    Format.valueOf(item.getString("format")),
                )
                .copy(
                    enabled = item.getBoolean("enabled"),
                    notes = item.optString("notes"),
                    widescreen = item.optBoolean("widescreen"),
                )
        }
    }

    fun save(root: File, disc: GameCubeDisc, codes: List<Code>) {
        val array = JSONArray()
        codes.forEach { code ->
            array.put(
                JSONObject()
                    .put("name", code.name)
                    .put("body", code.body)
                    .put("format", code.format.name)
                    .put("enabled", code.enabled)
                    .put("notes", code.notes)
                    .put("widescreen", code.widescreen),
            )
        }
        write(file(root, disc), array.toString())
    }

    /**
     * Writes only the INI owned by miel; returns launch options and the checkpoint code identity.
     */
    fun prepare(root: File, user: File, disc: GameCubeDisc): Array<String> {
        val codes = load(root, disc)
        val target = File(user, "User/GameSettings/${disc.id}r${disc.revision}.ini")
        if (codes.isEmpty()) return launchOptions(codes)
        if (target.exists() && !target.readText().startsWith(MARKER)) {
            throw IOException("Game codes cannot replace an existing Dolphin INI: ${target.name}")
        }
        val text = buildString {
            append(MARKER)
            for (format in Format.entries) {
                append("\n[${format.section}]\n")
                codes.forEachIndexed { index, code ->
                    if (code.format == format) append("\$miel-$index\n${code.body}\n")
                }
                append("\n[${format.section}_Enabled]\n")
                codes.forEachIndexed { index, code ->
                    if (code.format == format && code.enabled) append("\$miel-$index\n")
                }
            }
        }
        write(target, text)
        return launchOptions(codes)
    }

    private fun launchOptions(codes: List<Code>): Array<String> {
        val enabled = codes.filter { it.enabled }
        val text = enabled.joinToString("\n") { "${it.format.name} ${it.widescreen}\n${it.body}" }
        val identity =
            if (text.isEmpty()) ""
            else
                MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
                    "%02x".format(it)
                }
        return arrayOf(
            "dolphin_cheats_enabled",
            if (enabled.isEmpty()) "disabled" else "enabled",
            "dolphin_cheats_import",
            "disabled",
            "miel_game_codes",
            identity,
        ) +
            if (enabled.any { it.widescreen })
                arrayOf(
                    "dolphin_aspect_ratio",
                    "1",
                    "dolphin_widescreen_hack",
                    "disabled",
                )
            else emptyArray()
    }

    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
