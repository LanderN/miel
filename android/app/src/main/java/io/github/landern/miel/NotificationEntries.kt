package io.github.landern.miel

/** One visible card. Replaced values cannot be removed by an older completion timer. */
internal class NotificationEntries<K, V : Any> {
    private val entries = LinkedHashMap<K, V>()
    val latest: V? get() = entries.values.lastOrNull()

    fun put(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
    }

    fun remove(key: K) { entries.remove(key) }

    fun expire(key: K, value: V): Boolean {
        if (entries[key] !== value) return false
        entries.remove(key)
        return true
    }
}
