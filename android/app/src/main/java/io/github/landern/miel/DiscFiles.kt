package io.github.landern.miel

import java.io.IOException

/** Relative files belonging to a PlayStation cue sheet or disc playlist. */
object DiscFiles {
    private val cueFile = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\S+\s*$""")

    fun references(name: String, text: String): List<String> =
        when (name.substringAfterLast('.').lowercase()) {
            "cue" ->
                cueFile
                    .findAll(text.removePrefix("\uFEFF"))
                    .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    .toList()
            "m3u" ->
                text
                    .removePrefix("\uFEFF")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .toList()
            else -> emptyList()
        }

    fun relative(parent: String, reference: String): String {
        val path = reference.replace('\\', '/')
        if (path.startsWith('/') || ':' in path)
            throw IOException("Disc references must be relative: $reference")
        val parts = mutableListOf<String>()
        for (part in (parent + "/" + path).split('/')) {
            when (part) {
                "",
                "." -> Unit
                ".." ->
                    if (parts.isEmpty())
                        throw IOException("Disc reference leaves its folder: $reference")
                    else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    fun entry(files: List<String>): String =
        listOf("m3u", "cue", "chd", "pbp", "iso", "bin", "exe").firstNotNullOfOrNull { extension ->
            files.sorted().firstOrNull { it.substringAfterLast('.').equals(extension, true) }
        } ?: throw IOException("The archive holds no PlayStation game")
}
