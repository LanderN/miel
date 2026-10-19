package io.github.landern.miel

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.AtomicFile
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import java.text.Collator
import java.util.UUID
import java.util.concurrent.Executors

/** An app in the Apps tab. */
data class InstalledApp(
    /** The launcher activity's flattened component name. */
    val id: String,
    val label: String,
)

/**
 * The apps in the library's Apps tab: every launcher activity of this user except miel's, sorted by
 * label. Each icon is drawn once per app version to a PNG in the cache, which the library and
 * HeroView load by path.
 */
class AppList(
    private val context: Context,
    /** Runs on the main thread after start() and whenever an app changes. */
    private val changed: () -> Unit,
) : LauncherApps.Callback() {
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val icons = File(context.cacheDir, "app-icons")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The current apps, sorted by label. */
    @Volatile
    var apps: List<InstalledApp> = emptyList()
        private set

    @Volatile private var iconPaths: Map<String, String> = emptyMap()

    fun start() {
        launcher.registerCallback(this, main)
        refresh()
    }

    fun close() {
        launcher.unregisterCallback(this)
        worker.shutdownNow()
    }

    fun iconPath(id: String): String = iconPaths[id] ?: ""

    private fun refresh() {
        if (worker.isShutdown) return
        worker.execute {
            try {
                load()
            } catch (error: Exception) {
                Log.w(TAG, "Listing apps failed", error)
            }
        }
    }

    private fun load() {
        val collator = Collator.getInstance()
        val activities =
            launcher
                .getActivityList(null, Process.myUserHandle())
                .filter { it.componentName.packageName != context.packageName }
                .sortedWith { a, b -> collator.compare(label(a), label(b)) }
        val list = ArrayList<InstalledApp>()
        val paths = HashMap<String, String>()
        icons.mkdirs()
        for (info in activities) {
            val id = info.componentName.flattenToShortString()
            list.add(InstalledApp(id, label(info)))
            val icon = File(icons, iconName(info))
            if (!icon.isFile) writeIcon(info, icon)
            paths[id] = icon.path
        }
        icons.listFiles()?.forEach { if (!paths.containsValue(it.path)) it.delete() }
        iconPaths = paths
        apps = list
        main.post { if (!worker.isShutdown) changed() }
    }

    override fun onPackageRemoved(packageName: String, user: UserHandle) = refresh()

    override fun onPackageAdded(packageName: String, user: UserHandle) = refresh()

    override fun onPackageChanged(packageName: String, user: UserHandle) = refresh()

    override fun onPackagesAvailable(
        packageNames: Array<String>,
        user: UserHandle,
        replacing: Boolean,
    ) = refresh()

    override fun onPackagesUnavailable(
        packageNames: Array<String>,
        user: UserHandle,
        replacing: Boolean,
    ) = refresh()

    private companion object {
        const val TAG = "MielApps"
        const val ICON_SIZE = 192

        fun label(info: LauncherActivityInfo) = info.label.toString()

        /** Changes with the activity and with every install of its app. */
        fun iconName(info: LauncherActivityInfo): String {
            val key = info.componentName.flattenToString() + "\n" + info.applicationInfo.sourceDir
            return "${UUID.nameUUIDFromBytes(key.toByteArray())}.png"
        }

        fun writeIcon(info: LauncherActivityInfo, file: File) {
            val drawable = info.getIcon(DisplayMetrics.DENSITY_XXXHIGH)
            val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
            drawable.draw(Canvas(bitmap))
            val out = AtomicFile(file)
            val stream = out.startWrite()
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                out.finishWrite(stream)
            } catch (error: RuntimeException) {
                out.failWrite(stream)
                throw error
            } finally {
                bitmap.recycle()
            }
        }
    }
}
