package io.github.landern.miel

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/** Volume keys always adjust media volume, whichever display's window holds focus. */
object MediaVolume {
    fun key(context: Context, event: KeyEvent): Boolean {
        val direction =
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
                else -> return false
            }
        if (event.action == KeyEvent.ACTION_DOWN) {
            context
                .getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
        return true
    }
}
