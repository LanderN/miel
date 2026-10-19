package io.github.landern.miel

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Display

/** The launcher icon. Opens the library on the upper display. */
class LaunchActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        openLibrary(this, intent)
        finish()
    }

    companion object {
        /** Set by a finishing game, which may still be the top task when the library opens. */
        const val AFTER_GAME = "afterGame"

        fun openLibrary(caller: Activity, source: Intent) {
            // A running game first finishes its checkpoint and retry flow, then returns here.
            if (!source.getBooleanExtra(AFTER_GAME, false) && Displays.gameRunning(caller)) {
                caller.startActivity(
                    Intent(caller, CoreActivity::class.java)
                        .setAction(CoreActivity.RETURN_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                return
            }
            val library =
                Intent(caller, LibraryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(
                        CoreActivity.EXTRA_DEBUG_REPORTS,
                        source.getBooleanExtra(CoreActivity.EXTRA_DEBUG_REPORTS, false),
                    )
            caller.startActivity(
                library,
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        }
    }
}
