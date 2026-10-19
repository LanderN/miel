package io.github.landern.miel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Stages a disc set with the relative paths its playlist and cue sheets use. */
object DiscCopy {
    private fun descriptor(name: String) =
        name.substringAfterLast('.').lowercase() in setOf("cue", "m3u")

    private fun text(input: java.io.InputStream): String {
        val bytes = ByteArray(1024 * 1024 + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        if (size == bytes.size) throw IOException("Disc descriptor is too large")
        return String(bytes, 0, size, Charsets.UTF_8)
    }

    private fun descriptorText(file: File): String =
        file.inputStream().use { text(it).removePrefix("\uFEFF").replace('\\', '/') }

    private fun text(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { text(it) }
            ?: throw IOException("Cannot read disc descriptor")

    private fun resolve(context: Context, directory: Uri, path: String): Uri {
        if (directory.scheme == "file") return Uri.fromFile(File(directory.path!!, path))
        var current = directory
        for (part in path.split('/')) {
            val children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    current,
                    DocumentsContract.getDocumentId(current),
                )
            var found: String? = null
            context.contentResolver
                .query(
                    children,
                    arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    while (cursor.moveToNext()) if (cursor.getString(1) == part) {
                        found = cursor.getString(0)
                        break
                    }
                }
            current =
                DocumentsContract.buildDocumentUriUsingTree(
                    directory,
                    found ?: throw IOException("Missing disc file: $path"),
                )
        }
        return current
    }

    fun references(context: Context, uri: Uri, directory: Uri): List<String> {
        val name = uri.lastPathSegment.orEmpty()
        if (!descriptor(name)) return emptyList()
        return DiscFiles.references(name, text(context, uri)).map {
            resolve(context, directory, DiscFiles.relative("", it)).toString()
        }
    }

    fun make(
        context: Context,
        uri: Uri,
        filename: String,
        directory: Uri?,
        progress: (Float) -> Unit,
    ): RomCopy {
        val folder = File(context.cacheDir, "rom/disc-set")
        folder.deleteRecursively()
        if (!folder.mkdirs()) throw IOException("Cannot prepare disc cache")
        val hashes = sortedMapOf<String, String>()
        var total = 0L
        fun copy(source: java.io.InputStream, path: String) {
            if (hashes.size >= 256) throw IOException("Disc set exceeds 256 files")
            val target = File(folder, path)
            target.parentFile!!.mkdirs()
            hashes[path] = RomCopy.copy(source, target) {}
            total += target.length()
            if (total > (8L shl 30)) throw IOException("Disc set is larger than 8 GiB")
        }
        try {
            val entry: String
            if (filename.endsWith(".zip", true)) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zip ->
                        while (true) {
                            val item = zip.nextEntry ?: break
                            if (item.isDirectory) continue
                            val path = DiscFiles.relative("", item.name)
                            if (path.isEmpty() || path in hashes)
                                throw IOException("Invalid or repeated disc file: $path")
                            copy(zip, path)
                        }
                    }
                } ?: throw IOException("Cannot open disc archive")
                entry = DiscFiles.entry(hashes.keys.toList())
            } else {
                val base =
                    directory ?: throw IOException("Rescan the ROM folder to open this disc set")
                val visiting = HashSet<String>()
                fun stage(path: String, source: Uri) {
                    if (path in visiting) throw IOException("Disc references form a loop")
                    if (path in hashes) return
                    visiting.add(path)
                    context.contentResolver.openInputStream(source)?.use { copy(it, path) }
                        ?: throw IOException("Cannot read disc file: $path")
                    if (descriptor(path)) {
                        val contents = descriptorText(File(folder, path))
                        for (reference in DiscFiles.references(path, contents)) {
                            val next =
                                DiscFiles.relative(path.substringBeforeLast('/', ""), reference)
                            stage(next, resolve(context, base, next))
                        }
                    }
                    visiting.remove(path)
                }
                entry = filename
                stage(entry, uri)
            }
            // Validate references and normalize separators in the private copies.
            for (path in hashes.keys.filter(::descriptor)) {
                val file = File(folder, path)
                val contents = descriptorText(file)
                file.writeText(
                    if (path.endsWith(".m3u", true))
                        contents.lineSequence().joinToString("\n") { it.trim() }
                    else contents,
                )
                for (reference in DiscFiles.references(path, contents)) {
                    val target = DiscFiles.relative(path.substringBeforeLast('/', ""), reference)
                    if (target !in hashes) throw IOException("Missing disc file: $target")
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for ((path, hash) in hashes) digest.update("$path\u0000$hash\n".toByteArray())
            progress(1f)
            return RomCopy(File(folder, entry), digest.digest().toHex())
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }
}
