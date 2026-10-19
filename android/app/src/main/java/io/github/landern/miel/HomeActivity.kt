package io.github.landern.miel

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Android's Home entry point. Opens the library on the upper display. */
class HomeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LaunchActivity.openLibrary(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchActivity.openLibrary(this, intent)
    }
}
