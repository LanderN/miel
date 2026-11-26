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
     * The in-game save for a game on `platform`, named after its file without
     * its extension, with its folder created.
     */
    fun save(platform: String, filename: String): File {
        val extension = if (Emulator.sharesPokeDaisySaves(platform)) ".sav" else ".srm"
        return platformFolder(platform).resolve(filename.substringBeforeLast('.') + extension)
    }

    fun platformFolder(platform: String): File = folder("saves/$platform")

    /** A game's PSP memory stick, containing PSP/SAVEDATA. */
    fun memoryStick(filename: String): File =
        folder("saves/PSP/${filename.substringBeforeLast('.')}")

    /** A game's Dolphin user data, including its GameCube memory cards. */
    fun gameCube(filename: String): File =
        folder("saves/GameCube/${filename.substringBeforeLast('.')}")

    /** Where a game downloaded from RomM for `platform` is kept, with its folder created. */
    fun download(platform: String, filename: String): File =
        downloadFile(platform, filename).also {
            val folder = it.parentFile!!
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
        }

    /** The downloaded ROM path, without creating directories. */
    fun downloadFile(platform: String, filename: String): File =
        File(Environment.getExternalStorageDirectory(), "miel/roms/$platform/$filename")

    /** Removes only Azahar's compiled and transferable shader caches. */
    fun clearAzaharShaderCache(root: File = root()): Boolean {
        val shaders = File(root, "saves/Nintendo 3DS/Azahar/shaders")
        return !shaders.exists() || shaders.deleteRecursively()
    }

    private fun folder(path: String): File {
        val folder = File(root(), path)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create $folder")
        return folder
    }
}
