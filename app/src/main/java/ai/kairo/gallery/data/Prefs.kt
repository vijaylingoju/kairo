package ai.kairo.gallery.data

import android.content.Context

/** Small settings store. */
object Prefs {
    private const val FILE = "kairo"
    private const val KEY_SCREENSHOTS_SINCE = "screenshots_since_sec"
    private const val KEY_BALL = "floating_ball"
    private const val KEY_KEEP_READY = "keep_model_ready"
    private const val KEY_BALL_RIGHT = "ball_right"
    private const val KEY_BALL_Y = "ball_y"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 0 = off. Otherwise only screenshots added after this time (epoch seconds) get indexed. */
    fun screenshotsSince(ctx: Context): Long = prefs(ctx).getLong(KEY_SCREENSHOTS_SINCE, 0L)

    fun setWatchScreenshots(ctx: Context, on: Boolean) {
        val value = if (on) System.currentTimeMillis() / 1000 else 0L
        prefs(ctx).edit().putLong(KEY_SCREENSHOTS_SINCE, value).apply()
    }

    /** The floating ball over other apps (it also needs the "Display over other apps" permission). */
    fun ballEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BALL, false)

    fun setBallEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_BALL, on).apply()

    /** Demo mode: Gemma stays loaded instead of being freed after a few idle minutes. */
    fun keepModelReady(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_KEEP_READY, false)

    fun setKeepModelReady(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_KEEP_READY, on).apply()

    /** Where the user left the ball: which edge, and how far down (px; -1 = not moved yet). */
    fun ballRight(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BALL_RIGHT, true)

    fun ballY(ctx: Context): Int = prefs(ctx).getInt(KEY_BALL_Y, -1)

    fun setBallPosition(ctx: Context, right: Boolean, y: Int) =
        prefs(ctx).edit().putBoolean(KEY_BALL_RIGHT, right).putInt(KEY_BALL_Y, y).apply()
}
