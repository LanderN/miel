package io.github.landern.miel

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** A game's PSP savedata as a deterministic archive for RomM. */
object PspSaves {
    private const val PATH = "PSP/SAVEDATA"

    fun pack(memoryStick: File, archive: File): Boolean =
        SaveArchive.pack(mapOf(PATH to File(memoryStick, PATH)), archive)

    /** Restores a miel savedata archive, keeping the previous savedata folder as .bak. */
    fun unpack(memoryStick: File, archive: File) {
        ZipFile(archive).use { zip ->
            if (
                zip.entries().asSequence().none {
                    !it.isDirectory && it.name.startsWith("$PATH/") && ".." !in it.name
                }
            )
                throw IOException("The PSP save archive contains no PSP/SAVEDATA files")
        }
        SaveArchive.unpack(mapOf(PATH to File(memoryStick, PATH)), archive)
    }
}
