package io.github.landern.miel

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.Window

/**
 * Display selection and window helpers. The upper display is the primary one; the lower display's
 * ID is assigned at runtime, so it is chosen as the display that is not the primary.
 */
object Displays {
    const val TAG = "MielDisplays"

    /** Hides the status and navigation bars until the user swipes them in. */
    @Suppress("DEPRECATION")
    private const val IMMERSIVE =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN

    @Suppress("DEPRECATION")
    fun immersive(window: Window) {
        window.decorView.systemUiVisibility = IMMERSIVE
    }

    fun all(context: Context): Array<Display> =
        context.getSystemService(DisplayManager::class.java).displays

    /** The ID of the display `activity` is on. */
    @Suppress("DEPRECATION")
    fun own(activity: Activity): Int = activity.windowManager.defaultDisplay.displayId

    /** The first valid display other than `display`, or null. */
    fun other(context: Context, display: Int): Display? =
        all(context).firstOrNull { it.displayId != display && it.isValid }

    /** Whether a game's CoreActivity is on top of one of the app's tasks. */
    fun gameRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).appTasks.any {
            it.taskInfo?.topActivity?.className == CoreActivity::class.java.name
        }

    /** Runs `changed` whenever a display is added, removed or changed. */
    class Listener(private val context: Context, private val changed: () -> Unit) :
        DisplayManager.DisplayListener {
        fun register() {
            context.getSystemService(DisplayManager::class.java).registerDisplayListener(this, null)
        }

        fun unregister() {
            context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        }

        override fun onDisplayAdded(display: Int) = changed()

        override fun onDisplayRemoved(display: Int) = changed()

        override fun onDisplayChanged(display: Int) = changed()
    }
}
