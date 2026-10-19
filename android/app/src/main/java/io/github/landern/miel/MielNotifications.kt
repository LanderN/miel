package io.github.landern.miel

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/** In-app only; no Android notification permission or system overlay is needed. */
data class MielNotification(
    val title: String,
    val detail: String,
    val count: String = "Preparing",
    val progress: Float = -1f,
    val finished: Boolean = false,
    val warning: Boolean = false,
    val unavailableFraction: Float = 0f,
    val heading: String = "Library update",
) {
    val duration: Long get() = if (!finished) 0 else if (warning) 8000 else 3000
}

/** Main-thread host. Producers own a channel; generations prevent stale work/timers resurfacing. */
object MielNotifications {
    private val main = Handler(Looper.getMainLooper())
    private val entries = NotificationEntries<Channel, MielNotification>()
    private val listeners = LinkedHashSet<(MielNotification?) -> Unit>()

    fun observe(listener: (MielNotification?) -> Unit) {
        listeners.add(listener)
        listener(entries.latest)
    }

    fun remove(listener: (MielNotification?) -> Unit) { listeners.remove(listener) }

    private fun changed() {
        val value = entries.latest
        listeners.toList().forEach { it(value) }
    }

    class Channel {
        private val generation = AtomicInteger()
        fun begin(): Int {
            val ticket = generation.incrementAndGet()
            main.post { if (ticket == generation.get()) { entries.remove(this); changed() } }
            return ticket
        }
        fun cancel() { begin() }
        fun update(ticket: Int, value: MielNotification) {
            main.post {
                if (ticket != generation.get()) return@post
                entries.put(this, value)
                changed()
                if (value.duration > 0) main.postDelayed({
                    if (ticket == generation.get() && entries.expire(this, value)) {
                        changed()
                    }
                }, value.duration)
            }
        }
    }
}
