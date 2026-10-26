package io.github.landern.miel

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The selected game's artwork on the lower display. Each request carries a generation; an activity
 * that starts after close() finishes at once. An app opened on the lower display keeps it until
 * reclaim().
 */
class ArtworkActivity : Activity() {
    private var ticket = -1
    private var view: HeroView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        volumeControlStream = AudioManager.STREAM_MUSIC
        ticket = intent.getIntExtra(EXTRA_GENERATION, -1)
        if (!requested || ticket != generation) {
            finish()
            return
        }
        current = this
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        Displays.immersive(window)
        view =
            HeroView(this).also {
                val host = FrameLayout(this)
                host.addView(it, FrameLayout.LayoutParams(-1, -1))
                host.addView(NotificationView(this), FrameLayout.LayoutParams(-1, -1))
                setContentView(host)
                it.update(hero)
            }
        applyBrightness()
    }

    private fun applyBrightness() {
        window.attributes =
            window.attributes.apply {
                screenBrightness =
                    if (dimmed) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                    else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
    }

    override fun dispatchKeyEvent(event: KeyEvent) =
        MediaVolume.key(this, event) ||
            LibraryActivity.artworkKey(event) ||
            super.dispatchKeyEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent) =
        LibraryActivity.artworkMotion(event) || super.onGenericMotionEvent(event)

    @Deprecated("Back reaches the library")
    override fun onBackPressed() {
        LibraryActivity.artworkBack()
    }

    override fun onDestroy() {
        if (current == this) {
            current = null
            if (ticket == generation) requested = false
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_GENERATION = "generation"
        private var current: ArtworkActivity? = null
        private var requested = false
        private var yielded = false
        private var dimmed = false
        private var generation = 0
        private var hero = Hero("", "", "")

        /** Shows the artwork on the lower display, unless a game or an app holds it. */
        fun show(caller: Context) {
            if (yielded || Displays.gameRunning(caller)) return
            if (requested) return
            val lower = Displays.other(caller, Display.DEFAULT_DISPLAY) ?: return
            requested = true
            try {
                caller.startActivity(
                    Intent(caller, ArtworkActivity::class.java)
                        .putExtra(EXTRA_GENERATION, ++generation)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION,
                        ),
                    ActivityOptions.makeBasic().setLaunchDisplayId(lower.displayId).toBundle(),
                )
            } catch (error: RuntimeException) {
                requested = false
                Log.e(Displays.TAG, "Lower artwork failed", error)
            }
        }

        fun update(hero: Hero) {
            if (hero == this.hero) return
            this.hero = hero
            current?.takeIf { it.ticket == generation }?.view?.update(hero)
        }

        /** Leaves the lower display to an app that opens there. */
        fun yieldToApp() {
            yielded = true
            dim(false)
        }

        /** Uses minimum brightness while another app covers the upper library. */
        fun dim(dim: Boolean) {
            dimmed = dim && !yielded
            current?.applyBrightness()
        }

        fun reclaim() {
            yielded = false
        }

        fun close() {
            requested = false
            generation++
            current?.finishAndRemoveTask()
            current = null
        }
    }
}
