package io.github.landern.miel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import java.io.IOException

/**
 * The library's sounds, from assets/sounds. Calls run on the UI thread; sounds play only while the
 * activity is in the foreground.
 */
class UiSounds(context: Context) {
    /** A sound, played from assets/sounds/<lowercase name>.wav. */
    enum class Cue {
        NAVIGATE,
        CONFIRM,
        BACK,
        OPEN,
        ERROR,
    }

    private val pool =
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    private val samples = IntArray(Cue.entries.size)
    private val loaded = HashSet<Int>()
    private var active = false
    private var lastNavigation = 0L

    init {
        pool.setOnLoadCompleteListener { _, sample, status -> if (status == 0) loaded.add(sample) }
        for (cue in Cue.entries) {
            val name = cue.name.lowercase()
            try {
                context.assets.openFd("sounds/$name.wav").use {
                    samples[cue.ordinal] = pool.load(it, 1)
                }
            } catch (error: IOException) {
                Log.w("MielSounds", "Cannot load $name", error)
            }
        }
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) pool.autoPause()
    }

    fun play(cue: Cue) {
        val sample = samples[cue.ordinal]
        if (!active || sample !in loaded) return
        if (cue == Cue.NAVIGATE) {
            val now = SystemClock.uptimeMillis()
            if (now - lastNavigation < NAVIGATE_GAP) return
            lastNavigation = now
        }
        pool.play(sample, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        active = false
        pool.release()
    }

    private companion object {
        /** Navigation sounds closer together than this are dropped. */
        const val NAVIGATE_GAP = 50L
    }
}
