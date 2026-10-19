package io.github.landern.miel

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Deterministic save-folder ZIPs with backup copies on restore. */
internal object SaveArchive {
    fun pack(folders: Map<String, File>, archive: File): Boolean {
        val files =
            folders
                .flatMap { (path, folder) ->
                    folder
                        .walk()
                        .filter { it != folder }
                        .map {
                            "$path/${it.relativeTo(folder)}" + (if (it.isDirectory) "/" else "") to
                                it
                        }
                }
                .sortedBy { it.first }
        archive.delete()
        if (files.isEmpty()) return false
        val time = LocalDateTime.of(1980, 1, 1, 0, 0).atZone(ZoneId.systemDefault())
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            for ((name, file) in files) {
                val bytes = if (file.isFile) file.readBytes() else ByteArray(0)
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                entry.time = time.toInstant().toEpochMilli()
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        archive.setLastModified(files.maxOf { it.second.lastModified() })
        return true
    }

    fun unpack(folders: Map<String, File>, archive: File) {
        ZipFile(archive).use { zip ->
            for (folder in folders.values) {
                val backup = File(folder.path + ".bak")
                backup.deleteRecursively()
                if (folder.exists() && !folder.renameTo(backup)) {
                    throw IOException("Cannot keep $folder")
                }
            }
            for (entry in zip.entries()) {
                val path = folders.keys.firstOrNull { entry.name.startsWith("$it/") }
                if (path == null || ".." in entry.name) continue
                val target = File(folders.getValue(path), entry.name.removePrefix("$path/"))
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { input.copyTo(it) }
                }
            }
        }
    }
}
