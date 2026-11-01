package io.github.landern.miel

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/** The region-specific disc ID and revision used by Dolphin's game settings. */
data class GameCubeDisc(val id: String, val revision: Int) {
    companion object {
        fun read(input: InputStream, filename: String): GameCubeDisc {
            if (filename.substringAfterLast('.').equals("zip", true)) {
                val zip = ZipInputStream(input)
                val entry =
                    generateSequence { zip.nextEntry }
                        .firstOrNull {
                            !it.isDirectory &&
                                it.name.substringAfterLast('.').lowercase() in
                                    Core.DOLPHIN.extensions
                        } ?: throw IOException("No GameCube disc in archive")
                return read(zip, entry.name)
            }
            val stream = DataInputStream(input)
            val first = ByteArray(32).also(stream::readFully)
            val header =
                when (String(first, 0, 4, Charsets.ISO_8859_1)) {
                    "RVZ\u0001",
                    "WIA\u0001" -> {
                        skip(stream, 0x58L - first.size)
                        ByteArray(32).also(stream::readFully)
                    }
                    "CISO" -> {
                        if (first[8] != 1.toByte())
                            throw IOException("Missing GameCube disc header")
                        skip(stream, 0x8000L - first.size)
                        ByteArray(32).also(stream::readFully)
                    }
                    else -> {
                        val bytes = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN)
                        if (bytes.getInt(0) == 0xB10BC001.toInt()) {
                            val blocks = bytes.getInt(28).toLong() and 0xffffffffL
                            val offset = java.lang.Long.reverseBytes(stream.readLong())
                            skip(stream, blocks * 12 - 8 + (offset and Long.MAX_VALUE))
                            val decoded =
                                if (offset < 0) stream
                                else DataInputStream(InflaterInputStream(stream))
                            ByteArray(32).also(decoded::readFully)
                        } else first
                    }
                }
            val id = String(header, 0, 6, Charsets.US_ASCII)
            if (
                !Regex("[A-Z0-9]{6}").matches(id) ||
                    ByteBuffer.wrap(header).getInt(28) != 0xC2339F3D.toInt()
            ) {
                throw IOException("Not a GameCube disc image")
            }
            return GameCubeDisc(id, header[7].toInt() and 255)
        }

        private fun skip(input: InputStream, count: Long) {
            if (count < 0) throw IOException("Invalid disc header")
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped
                else if (input.read() < 0) throw IOException("Incomplete disc header")
                else remaining--
            }
        }
    }
}
