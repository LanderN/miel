package io.github.landern.miel

import android.content.Context
import java.io.File

/** Bundled runtime files extracted for cores that require filesystem paths. */
object CoreAssets {
    fun system(context: Context, core: Core): File {
        val (asset, folder) =
            when (core) {
                Core.PPSSPP -> "ppsspp" to "PPSSPP"
                Core.DOLPHIN -> "dolphin-emu" to "dolphin-emu"
                else -> return File(SaveStorage.root(), "system")
            }
        val root = File(context.filesDir, "core-assets/$asset-${core.version}")
        val marker = File(root, ".complete")
        if (!marker.isFile) {
            root.deleteRecursively()
            copy(context, asset, File(root, folder))
            marker.writeText(core.version)
        }
        return root
    }

    private fun copy(context: Context, asset: String, target: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isNotEmpty()) {
            target.mkdirs()
            for (child in children) copy(context, "$asset/$child", File(target, child))
        } else {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
