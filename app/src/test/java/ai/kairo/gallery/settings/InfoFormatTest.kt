package ai.kairo.gallery.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class InfoFormatTest {

    @Test
    fun sizesUseDecimalUnitsLikeSettings() {
        assertEquals("512 GB", InfoFormat.size(512_000_000_000))
        assertEquals("45.7 GB", InfoFormat.size(45_670_000_000))
        assertEquals("2 GB", InfoFormat.size(2_000_000_000))
        assertEquals("750 MB", InfoFormat.size(750_000_000))
    }

    @Test
    fun ramRoundsUpToTheSizeOnTheBox() {
        val gib = 1024L * 1024 * 1024
        assertEquals(16, InfoFormat.marketedRamGb(15 * gib + gib / 5)) // ~15.2 GiB reported on a 16 GB phone
        assertEquals(12, InfoFormat.marketedRamGb(11 * gib))
        assertEquals(8, InfoFormat.marketedRamGb(8 * gib))
    }

    @Test
    fun patchAgeReadsNaturally() {
        assertEquals("today", InfoFormat.ago(0))
        assertEquals("5 days ago", InfoFormat.ago(5))
        assertEquals("3 weeks ago", InfoFormat.ago(21))
        assertEquals("about 1 month ago", InfoFormat.ago(35))
        // The iQOO's 2026-08-01 patch, asked on 2026-09-26
        assertEquals("about 2 months ago", InfoFormat.ago(56))
        assertEquals("over 1 year ago", InfoFormat.ago(400))
    }

    @Test
    fun durationsAndTemperature() {
        assertEquals("12 min", InfoFormat.duration(12 * 60_000L))
        assertEquals("5 h 3 min", InfoFormat.duration((5 * 60 + 3) * 60_000L))
        assertEquals("2 days 4 h", InfoFormat.duration((52 * 60 + 10) * 60_000L))
        assertEquals("31.2 °C", InfoFormat.temperature(31.2f))
        assertEquals("40 °C", InfoFormat.temperature(40f))
    }

    @Test
    fun datesAreShort() {
        assertEquals("1 Aug 2026", InfoFormat.date(LocalDate.of(2026, 8, 1)))
    }
}
