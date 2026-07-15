package com.psychojelly.joancues

import android.content.Context

/** Tiny settings store: which mode this tablet is. */
object Prefs {
    const val MODE_OPERATOR = "operator"
    const val MODE_PERFORMER = "performer"

    private const val FILE = "joancues"
    private const val KEY_MODE = "mode"

    fun savedMode(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, null)

    fun saveMode(context: Context, mode: String) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()

    fun clearMode(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_MODE).apply()
}
