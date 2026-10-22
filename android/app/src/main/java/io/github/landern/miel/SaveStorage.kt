package io.github.landern.miel

import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * Game saves, checkpoints and the core's system folder live in /sdcard/miel, so they survive
 * reinstalls. Writing there needs All files access.
 */
object SaveStorage {
    fun available(): Boolean = Environment.isExternalStorageManager()

    /** The shared folder, with its system, saves and states folders created. */
    fun root(): File {
        val root = File(Environment.getExternalStorageDirectory(), "miel")
        for (name in listOf("system", "saves", "states")) {
            val folder = File(root, name)
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
        }
        return root
    }

    /**
     * The in-game save the host keeps for `core`'s game in `filename`, named after the file without
     * its extension, with its folder created.
     */
    fun save(core: Core, filename: String): File =
        folder("saves/${core.coreName}").resolve(filename.substringBeforeLast('.') + ".srm")

    /** A game's PSP memory stick, containing PSP/SAVEDATA. */
    fun memoryStick(filename: String): File =
        folder("saves/PPSSPP/${filename.substringBeforeLast('.')}")

    /** Where a game downloaded from RomM for `platform` is kept, with its folder created. */
    fun download(platform: String, filename: String): File =
        folder("roms/$platform").resolve(filename)

    /** Removes only Azahar's compiled and transferable shader caches. */
    fun clearAzaharShaderCache(root: File = root()): Boolean {
        val shaders = File(root, "saves/Azahar/shaders")
        return !shaders.exists() || shaders.deleteRecursively()
    }

    private fun folder(path: String): File {
        val folder = File(root(), path)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
        return folder
    }
}
