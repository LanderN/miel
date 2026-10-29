package io.github.landern.miel

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** A game's GameCube memory cards as a deterministic archive for RomM. */
object DolphinSaves {
    private const val PATH = "User/GC"

    fun pack(userDirectory: File, archive: File): Boolean =
        SaveArchive.pack(mapOf(PATH to File(userDirectory, PATH)), archive)

    /** Restores memory cards, keeping the previous GameCube folder as .bak. */
    fun unpack(userDirectory: File, archive: File) {
        ZipFile(archive).use { zip ->
            if (
                zip.entries().asSequence().none {
                    !it.isDirectory && it.name.startsWith("$PATH/") && ".." !in it.name
                }
            )
                throw IOException("The GameCube save archive contains no User/GC files")
        }
        SaveArchive.unpack(mapOf(PATH to File(userDirectory, PATH)), archive)
    }
}
