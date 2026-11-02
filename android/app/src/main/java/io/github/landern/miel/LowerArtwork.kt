package io.github.landern.miel

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The library's Presentation on the lower display: the selected game's artwork, or the view
 * `switcher` makes while another app covers the library. While an app opened there holds the lower
 * display, it shrinks to a close button that takes the display back.
 */
class LowerArtwork(
    private val activity: ComponentActivity,
    private val switcher: (Context) -> View,
) {
    private var lower: Presentation? = null
    private var view: HeroView? = null
    private var host: FrameLayout? = null
    private var apps: View? = null
    private var handle: View? = null
    private var yielded = false
    private var covered = false
    private var hero = Hero("", "", "")

    /** Shows the Presentation on the lower display. */
    fun show() {
        val target = Displays.other(activity, Display.DEFAULT_DISPLAY)
        lower?.let {
            if (target != null && it.display.displayId == target.displayId && it.isShowing)
                return applyState()
        }
        close()
        if (target == null) return
        try {
            val presentation = Presentation(activity, target)
            presentation.window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            val hero = HeroView(presentation.context)
            val host = FrameLayout(presentation.context)
            host.addView(hero, FrameLayout.LayoutParams(-1, -1))
            host.addView(NotificationView(presentation.context), FrameLayout.LayoutParams(-1, -1))
            val outer = FrameLayout(presentation.context)
            outer.addView(host, FrameLayout.LayoutParams(-1, -1))
            val apps = switcher(presentation.context)
            outer.addView(apps, FrameLayout.LayoutParams(-1, -1))
            val handle = closeButton(presentation.context)
            outer.addView(handle, FrameLayout.LayoutParams(-2, -2))
            presentation.setContentView(outer)
            presentation.window?.decorView?.let {
                it.setViewTreeLifecycleOwner(activity)
                it.setViewTreeSavedStateRegistryOwner(activity)
            }
            presentation.window?.let(Displays::immersive)
            hero.update(this.hero)
            lower = presentation
            view = hero
            this.host = host
            this.apps = apps
            this.handle = handle
            applyState()
            presentation.show()
        } catch (error: RuntimeException) {
            Log.e(Displays.TAG, "Lower artwork failed", error)
            close()
        }
    }

    fun update(hero: Hero) {
        if (hero == this.hero) return
        this.hero = hero
        view?.update(hero)
    }

    /** Leaves the lower display to an app that opens there, keeping only the close button. */
    fun yieldToApp() {
        yielded = true
        applyState()
    }

    /**
     * Shows the switcher while another app covers the upper library. Display brightness is left
     * alone, so a keyboard on the lower display stays readable.
     */
    fun dim(dim: Boolean) {
        covered = dim
        applyState()
    }

    fun reclaim() {
        yielded = false
        applyState()
    }

    private fun applyState() {
        val window = lower?.window ?: return
        host?.visibility = if (!yielded && !covered) View.VISIBLE else View.GONE
        apps?.visibility = if (!yielded && covered) View.VISIBLE else View.GONE
        handle?.visibility = if (yielded) View.VISIBLE else View.GONE
        val size =
            if (yielded) WindowManager.LayoutParams.WRAP_CONTENT
            else WindowManager.LayoutParams.MATCH_PARENT
        val margin = if (yielded) (12 * activity.resources.displayMetrics.density).toInt() else 0
        window.attributes =
            window.attributes.apply {
                width = size
                height = size
                gravity = Gravity.TOP or Gravity.END
                x = margin
                y = margin
                flags =
                    if (yielded) flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    else flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
            }
        window.setBackgroundDrawable(ColorDrawable(if (yielded) Color.TRANSPARENT else Color.BLACK))
    }

    /** A round × that takes the lower display back from the app there. */
    private fun closeButton(context: Context): View {
        val size = (40 * context.resources.displayMetrics.density).toInt()
        return TextView(context).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF303638.toInt())
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xEBFFFEF8.toInt())
                }
            minWidth = size
            minHeight = size
            contentDescription = "Close app"
            setOnClickListener { reclaim() }
        }
    }

    fun close() {
        lower?.dismiss()
        lower = null
        view = null
        host = null
        apps = null
        handle = null
    }
}
