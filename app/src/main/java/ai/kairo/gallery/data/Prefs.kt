package ai.kairo.gallery.data

import android.content.Context

/** Small settings store. */
object Prefs {
    private const val FILE = "kairo"
    private const val KEY_SCREENSHOTS_SINCE = "screenshots_since_sec"

    /** 0 = off. Otherwise only screenshots added after this time (epoch seconds) get indexed. */
    fun screenshotsSince(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_SCREENSHOTS_SINCE, 0L)

    fun setWatchScreenshots(ctx: Context, on: Boolean) {
        val value = if (on) System.currentTimeMillis() / 1000 else 0L
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SCREENSHOTS_SINCE, value).apply()
    }
}
