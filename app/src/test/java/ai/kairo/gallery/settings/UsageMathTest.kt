package ai.kairo.gallery.settings

import ai.kairo.gallery.settings.UsageMath.Event
import ai.kairo.gallery.settings.UsageMath.PAUSED
import ai.kairo.gallery.settings.UsageMath.RESUMED
import ai.kairo.gallery.settings.UsageMath.SCREEN_OFF
import ai.kairo.gallery.settings.UsageMath.UNLOCKED
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageMathTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val start = 100 * 24 * hour // "midnight"
    private val home = setOf("launcher")

    private fun at(minutes: Long, type: Int, pkg: String = "") = Event(start + minutes * min, type, pkg)

    @Test
    fun countsEachAppUntilItLeavesTheFront() {
        val day = UsageMath.summarize(
            listOf(
                at(600, RESUMED, "insta"),
                at(630, PAUSED, "insta"),
                at(630, RESUMED, "launcher"),     // home screen doesn't count
                at(632, RESUMED, "chat"),
                at(650, RESUMED, "insta"),        // another app to the front ends "chat"
                at(660, SCREEN_OFF),              // screen off ends "insta"
            ),
            start, start + 24 * hour, home,
        )
        assertEquals(mapOf("insta" to 40 * min, "chat" to 18 * min), day.perApp)
        assertEquals(58 * min, day.totalMs)
        assertEquals(0L, day.lateNightMs)
    }

    @Test
    fun appOpenAtMidnightCountsFromMidnightAndLateNightIsTracked() {
        val day = UsageMath.summarize(
            listOf(
                at(-30, RESUMED, "video"),        // opened at 23:30 the day before
                at(45, PAUSED, "video"),
                at(290, RESUMED, "chat"),         // 04:50 → 05:20: only 10 min is "after midnight"
                at(320, PAUSED, "chat"),
            ),
            start, start + 24 * hour, home,
        )
        assertEquals(mapOf("video" to 45 * min, "chat" to 30 * min), day.perApp)
        assertEquals(55 * min, day.lateNightMs)
    }

    @Test
    fun stillOpenAppCountsUntilNowAndUnlocksOnlyFromToday() {
        val now = start + 12 * hour
        val day = UsageMath.summarize(
            listOf(
                at(-10, UNLOCKED),                // yesterday's unlock
                at(100, UNLOCKED),
                at(700, UNLOCKED),
                at(700, RESUMED, "maps"),         // still in front
            ),
            start, now, home,
        )
        assertEquals(mapOf("maps" to 20 * min), day.perApp)
        assertEquals(2, day.unlocks)
    }

    @Test
    fun lateResumeOfAnotherAppIsIgnored() {
        // Some phones log B's RESUMED before A's PAUSED; A's pause must not cut B short.
        val day = UsageMath.summarize(
            listOf(
                at(10, RESUMED, "a"),
                at(20, RESUMED, "b"),
                at(20, PAUSED, "a"),
                at(50, PAUSED, "b"),
            ),
            start, start + 24 * hour, home,
        )
        assertEquals(mapOf("a" to 10 * min, "b" to 30 * min), day.perApp)
    }
}
