package io.github.landern.miel

import android.app.Activity
import android.app.Presentation
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The selected game's artwork on the lower display, as a Presentation of the library. An app opened
 * on the lower display keeps it until reclaim().
 */
class LowerArtwork(private val activity: Activity) {
    private var lower: Presentation? = null
    private var view: HeroView? = null
    private var yielded = false
    private var dimmed = false
    private var hero = Hero("", "", "")

    /** Shows the artwork on the lower display, unless an app holds it. */
    fun show() {
        if (yielded) return
        val target = Displays.other(activity, Display.DEFAULT_DISPLAY)
        lower?.let {
            if (target != null && it.display.displayId == target.displayId && it.isShowing) return
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
            presentation.setContentView(host)
            presentation.window?.let(Displays::immersive)
            hero.update(this.hero)
            lower = presentation
            view = hero
            applyBrightness()
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

    /** Leaves the lower display to an app that opens there. */
    fun yieldToApp() {
        yielded = true
        dimmed = false
        close()
    }

    /** Uses minimum brightness while another app covers the upper library. */
    fun dim(dim: Boolean) {
        dimmed = dim && !yielded
        applyBrightness()
    }

    private fun applyBrightness() {
        val window = lower?.window ?: return
        window.attributes =
            window.attributes.apply {
                screenBrightness =
                    if (dimmed) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                    else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
    }

    fun reclaim() {
        yielded = false
    }

    fun close() {
        lower?.dismiss()
        lower = null
        view = null
    }
}
