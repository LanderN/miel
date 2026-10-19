package io.github.landern.miel

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * A private copy of a ROM, which the core opens by path. The document provider's file is only read.
 */
class RomCopy
internal constructor(
    val file: File,
    /** SHA-256 of the content, which names the game's checkpoint. */
    val sha256: String,
) {
    companion object {
        private const val MAX_SIZE = 8L shl 30

        /** Free space left on the cache's volume after a copy. */
        private const val RESERVE = 64L shl 20

        private const val CACHE_CHUNK = 32L shl 20

        /**
         * Copies `uri` to cache/rom/<filename>, or from a ZIP archive its first file with one of
         * `extensions` under that file's name, reporting the fraction of the document read when its
         * size is known. The core names the game's in-game save after the copy. Interrupting the
         * thread cancels the copy.
         */
        fun make(
            context: Context,
            uri: Uri,
            filename: String?,
            extensions: Set<String>,
            progress: (Float) -> Unit,
        ): RomCopy {
            val folder = File(context.cacheDir, "rom")
            if (!folder.isDirectory && !folder.mkdirs())
                throw IOException("Cannot prepare ROM cache")
            val name = filename?.substringAfterLast('/').orEmpty()
            val archive = extension(name) == "zip"
            if (!archive && extension(name) !in extensions) {
                throw IOException("Unsupported file type: $name")
            }
            val size = size(context, uri)
            if (size > MAX_SIZE) throw IOException("ROM is larger than 8 GiB")
            if (size >= 0 && size > StatFs(folder.absolutePath).availableBytes - RESERVE) {
                throw IOException("Not enough space for a private ROM copy")
            }
            val temporary = File.createTempFile("staging-", ".tmp", folder)
            try {
                val input =
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Folder access no longer allows reading this game")
                val (romName, sha256) =
                    Counting(input).use { counting ->
                        val read = {
                            if (size > 0) progress(minOf(1f, counting.count / size.toFloat()))
                        }
                        if (archive) {
                            val zip = ZipInputStream(counting)
                            val entry =
                                generateSequence { zip.nextEntry }
                                    .firstOrNull {
                                        !it.isDirectory && extension(it.name) in extensions
                                    }
                                    ?: throw IOException("The archive holds no game this core runs")
                            entry.name.substringAfterLast('/').substringAfterLast('\\') to
                                copy(zip, temporary, read)
                        } else {
                            name to copy(counting, temporary, read)
                        }
                    }
                val rom = File(folder, romName)
                Files.move(temporary.toPath(), rom.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return RomCopy(rom, sha256)
            } finally {
                temporary.delete()
            }
        }

        /** Deletes the files beside `rom`. */
        fun deleteOthers(rom: File) {
            if (rom.parentFile?.name == "disc-set") return
            rom.parentFile?.listFiles { _, name -> name != rom.name }?.forEach { it.delete() }
        }

        /** The document's size, or -1 when the provider does not know it. */
        private fun size(context: Context, uri: Uri): Long =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                } ?: -1

        private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

        /**
         * Copies and syncs `input` to `target`, calling `read` after each chunk; returns the
         * SHA-256.
         */
        internal fun copy(input: InputStream, target: File, read: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var synced = 0L
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    copied += count
                    if (copied > MAX_SIZE) throw IOException("ROM is larger than 8 GiB")
                    if (count > StatFs(target.parentFile!!.absolutePath).availableBytes - RESERVE)
                        throw IOException("Not enough space for a private ROM copy")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    if (copied - synced >= CACHE_CHUNK) {
                        output.fd.sync()
                        discardCache(output.fd, synced, copied - synced)
                        synced = copied
                    }
                    read()
                }
                if (copied == 0L) throw IOException("The selected file is empty")
                output.fd.sync()
                discardCache(output.fd)
            }
            return digest.digest().toHex()
        }

        /** Releases sequential-copy pages before the core allocates its working memory. */
        private fun discardCache(fd: FileDescriptor, offset: Long = 0, length: Long = 0) {
            try {
                ParcelFileDescriptor.dup(fd).use {
                    val error = CoreHost.discardRomCache(it.fd, offset, length)
                    if (error != 0) Log.d("MielRom", "ROM cache advice unavailable: $error")
                }
            } catch (error: IOException) {
                Log.d("MielRom", "ROM cache advice unavailable", error)
            }
        }
    }

    /** Counts the bytes read through it. */
    private class Counting(private val source: InputStream) : FilterInputStream(source) {
        var count = 0L
        private var discarded = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also {
                if (it > 0) {
                    count += it
                    if (count - discarded >= CACHE_CHUNK) {
                        (source as? FileInputStream)?.let { input ->
                            discardCache(input.fd, discarded, count - discarded)
                        }
                        discarded = count
                    }
                }
            }

        override fun close() {
            try {
                (source as? FileInputStream)?.let { discardCache(it.fd) }
            } finally {
                super.close()
            }
        }
    }
}
