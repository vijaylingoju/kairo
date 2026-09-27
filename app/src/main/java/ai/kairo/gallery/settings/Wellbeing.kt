package ai.kairo.gallery.settings

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import ai.kairo.gallery.settings.SettingIds.APP_TIMER
import ai.kairo.gallery.settings.SettingIds.BEDTIME_MODE
import ai.kairo.gallery.settings.SettingIds.FOCUS_MODE
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** "Usage access" (a special app access the user turns on). Also needed to read other apps' cache sizes. */
fun Context.hasUsageAccess(): Boolean {
    val mode = getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    return if (mode == AppOpsManager.MODE_DEFAULT) {
        checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    } else mode == AppOpsManager.MODE_ALLOWED
}

/** The app's name as the launcher shows it; the package name if it isn't visible to us. */
fun Context.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Screen time from Android's own usage data. Kairo only reads it; timers and Bedtime mode live in Digital Wellbeing. */
class Wellbeing(private val context: Context) {

    private val usage = context.getSystemService(UsageStatsManager::class.java)

    fun screenTime(setting: KbSetting): SettingsResponse {
        if (!context.hasUsageAccess()) return needsAccess(setting.id)

        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterdayStart = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ignore = ignoredPackages()
        val today = day(todayStart, System.currentTimeMillis(), ignore)
        val yesterday = day(yesterdayStart, todayStart, ignore)
        val top = today.perApp.entries.sortedByDescending { it.value }.take(TOP_APPS)

        val rows = buildList {
            add("Today" to InfoFormat.duration(today.totalMs))
            add("Yesterday" to InfoFormat.duration(yesterday.totalMs))
            add("Unlocks today" to today.unlocks.toString())
            if (today.lateNightMs > 0) add("After midnight" to InfoFormat.duration(today.lateNightMs))
            top.forEachIndexed { i, (pkg, ms) -> add("${i + 1}. ${context.appLabel(pkg)}" to InfoFormat.duration(ms)) }
        }

        val items = buildList {
            top.firstOrNull()?.takeIf { it.value >= TIMER_AFTER_MS }?.let { (pkg, ms) ->
                val name = context.appLabel(pkg)
                add(Suggestion(APP_TIMER, Actions.OPEN, "Set a timer for $name", "You've spent ${InfoFormat.duration(ms)} on it today.", "Open", value = pkg))
            }
            if (today.lateNightMs >= LATE_NIGHT_MS) {
                add(
                    Suggestion(
                        BEDTIME_MODE, Actions.OPEN, "Turn on Bedtime mode",
                        "You used your phone for ${InfoFormat.duration(today.lateNightMs)} after midnight. It silences the phone and turns the screen grey at night.",
                        "Open",
                    ),
                )
            }
            if (today.unlocks >= MANY_UNLOCKS) {
                add(
                    Suggestion(
                        FOCUS_MODE, Actions.OPEN, "Pause distracting apps",
                        "You've unlocked your phone ${today.unlocks} times today. Focus mode pauses the apps you pick.",
                        "Open",
                    ),
                )
            }
        }

        val unlocks = if (today.unlocks == 1) "once" else "${today.unlocks} times"
        val text = "You've used your phone for ${InfoFormat.duration(today.totalMs)} today and unlocked it $unlocks."
        return if (items.isEmpty()) {
            SettingsResponse.Facts("$text That looks balanced.", rows, setting.guide?.intentSpec(), "Open Digital Wellbeing")
        } else {
            SettingsResponse.Suggestions("$text These can help:", items, rows)
        }
    }

    private fun day(start: Long, end: Long, ignore: Set<String>): UsageMath.DayUse {
        val events = mutableListOf<UsageMath.Event>()
        // Start earlier so an app that was already open at midnight counts from midnight.
        val raw = usage.queryEvents(start - LOOKBACK_MS, end)
        val event = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            if (event.eventType in UsageMath.TYPES) events += UsageMath.Event(event.timeStamp, event.eventType, event.packageName)
        }
        return UsageMath.summarize(events, start, end, ignore)
    }

    /** Home screen and system UI aren't "using an app", as in Digital Wellbeing. */
    private fun ignoredPackages(): Set<String> {
        val home = context.packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return setOfNotNull(home, "com.android.systemui")
    }

    private fun needsAccess(settingId: String) = SettingsResponse.NeedsPermission(
        "To see your screen time I need Usage access. Tap Grant, turn it on for Kairo, then come back and " +
            "tap Try again. The data never leaves your phone.",
        IntentSpec(Settings.ACTION_USAGE_ACCESS_SETTINGS, withPackageUri = true),
        settingId,
        Actions.SHOW,
    )

    private companion object {
        const val TOP_APPS = 3
        const val LOOKBACK_MS = 6 * 3600 * 1000L
        const val TIMER_AFTER_MS = 3600 * 1000L
        const val LATE_NIGHT_MS = 30 * 60 * 1000L
        const val MANY_UNLOCKS = 80
    }
}

/** Pure screen-time maths over usage events, unit-tested on the JVM. */
object UsageMath {

    data class Event(val time: Long, val type: Int, val pkg: String)

    data class DayUse(val perApp: Map<String, Long>, val unlocks: Int, val lateNightMs: Long) {
        val totalMs: Long get() = perApp.values.sum()
    }

    // UsageEvents.Event constants, inlined at compile time.
    const val RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
    const val PAUSED = UsageEvents.Event.ACTIVITY_PAUSED
    const val STOPPED = UsageEvents.Event.ACTIVITY_STOPPED
    const val SCREEN_OFF = UsageEvents.Event.SCREEN_NON_INTERACTIVE
    const val UNLOCKED = UsageEvents.Event.KEYGUARD_HIDDEN
    val TYPES = setOf(RESUMED, PAUSED, STOPPED, SCREEN_OFF, UNLOCKED)

    /** "After midnight" = 00:00 to 05:00. */
    const val LATE_NIGHT_END_MS = 5 * 3600 * 1000L

    /**
     * One app is in front at a time: it counts from ACTIVITY_RESUMED until it pauses, another app comes to the
     * front, or the screen turns off. Apps in [ignore] (home screen, system UI) don't count. [events] may start
     * before [start]; only the part inside [start, end] counts.
     */
    fun summarize(events: List<Event>, start: Long, end: Long, ignore: Set<String>): DayUse {
        val perApp = mutableMapOf<String, Long>()
        var lateNight = 0L
        var unlocks = 0
        var current: String? = null
        var since = 0L
        val lateNightEnd = start + LATE_NIGHT_END_MS

        fun close(at: Long) {
            val pkg = current ?: return
            val from = max(since, start)
            val to = min(at, end)
            if (to > from) {
                perApp[pkg] = (perApp[pkg] ?: 0L) + (to - from)
                lateNight += (min(to, lateNightEnd) - from).coerceAtLeast(0)
            }
            current = null
        }

        for (e in events) {
            if (e.time > end) break
            when (e.type) {
                RESUMED -> {
                    close(e.time)
                    if (e.pkg !in ignore) {
                        current = e.pkg
                        since = e.time
                    }
                }
                PAUSED, STOPPED -> if (e.pkg == current) close(e.time)
                SCREEN_OFF -> close(e.time)
                UNLOCKED -> if (e.time >= start) unlocks++
            }
        }
        close(end)
        return DayUse(perApp, unlocks, lateNight)
    }
}
