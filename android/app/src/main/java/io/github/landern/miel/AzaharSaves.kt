package io.github.landern.miel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

/**
 * A 3DS game's saves in Azahar's user folder, as one archive for RomM: its save data and, by the
 * usual convention, its extra data, whose ID is the title's unique ID. The archive stores its files
 * and folders uncompressed, sorted and with a fixed time, so the same saves always give the same
 * bytes.
 */
object AzaharSaves {
    private const val CONSOLE = "00000000000000000000000000000000"

    /** The title ID in a 3DS ROM's first 0x120 bytes, from its NCSD or NCCH header, or 0. */
    fun titleId(header: ByteArray): Long {
        if (header.size < 0x120) return 0
        val at =
            when (String(header, 0x100, 4, Charsets.US_ASCII)) {
                "NCSD" -> 0x108
                "NCCH" -> 0x118
                else -> return 0
            }
        return ByteBuffer.wrap(header, at, 8).order(LITTLE_ENDIAN).long
    }

    /**
     * Packs title `titleId`'s saves into `archive`, dated with the newest file. Returns false, with
     * no archive, when the game has no saves yet.
     */
    fun pack(titleId: Long, archive: File): Boolean = SaveArchive.pack(folders(titleId), archive)

    /** Replaces title `titleId`'s saves with `archive`'s, keeping each old folder as .bak. */
    fun unpack(titleId: Long, archive: File) = SaveArchive.unpack(folders(titleId), archive)

    /** The game's save folders by archive path, which is their path in Azahar's SD folder. */
    private fun folders(titleId: Long): Map<String, File> {
        val sd = File(SaveStorage.root(), "saves/Azahar/sdmc/Nintendo 3DS/$CONSOLE/$CONSOLE")
        val high = "%08x".format(titleId ushr 32)
        val low = "%08x".format(titleId and 0xffffffffL)
        val extra = "%08X".format((titleId ushr 8) and 0xfffff)
        return listOf("title/$high/$low/data", "extdata/00000000/$extra").associateWith {
            File(sd, it)
        }
    }
}
