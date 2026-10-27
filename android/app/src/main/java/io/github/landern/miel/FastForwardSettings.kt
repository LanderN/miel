package io.github.landern.miel

import android.content.Context

/** Each game's FAST_FORWARD_OPTIONS values, by game ID, chosen in PlayControls. */
object FastForwardSettings {
    private const val FILE = "fast-forward"

    fun load(context: Context, id: String): Map<String, String> {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return FAST_FORWARD_OPTIONS.associate {
            it.key to it.value(preferences.getString("$id.${it.key}", null))
        }
    }

    fun save(context: Context, id: String, key: String, value: String) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("$id.$key", value)
            .apply()
    }
}
