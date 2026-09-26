package ai.kairo.gallery.settings

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import android.view.Display
import ai.kairo.gallery.settings.SettingIds.BATTERY_INFO
import ai.kairo.gallery.settings.SettingIds.DEVICE_INFO
import ai.kairo.gallery.settings.SettingIds.SOFTWARE_UPDATE
import ai.kairo.gallery.settings.SettingIds.STORAGE_INFO
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Read-only answers about the phone (the info tier). Nothing here needs a permission. */
class PhoneInfo(private val context: Context) {

    fun answer(setting: KbSetting): SettingsResponse? = when (setting.id) {
        DEVICE_INFO -> deviceInfo(setting)
        STORAGE_INFO -> storageInfo(setting)
        BATTERY_INFO -> batteryInfo(setting)
        SOFTWARE_UPDATE -> softwareUpdate(setting)
        else -> null
    }

    private fun deviceInfo(setting: KbSetting): SettingsResponse {
        val name = deviceName()
        val skin = skinName()
        val rows = buildList {
            add("Model" to if (Build.MODEL in name) name else "$name (${Build.MODEL})")
            add("Android version" to Build.VERSION.RELEASE)
            skin?.let { add("Software" to it) }
            add("Build number" to Build.DISPLAY)
            securityPatch()?.let { add("Security patch" to InfoFormat.date(it)) }
            add("Processor" to "${chipset()} · ${Runtime.getRuntime().availableProcessors()} cores")
            add("RAM" to "${InfoFormat.marketedRamGb(memory().totalMem)} GB")
            storage()?.let { (total, free) -> add("Storage" to "${InfoFormat.size(free)} free of ${InfoFormat.size(total)}") }
            screen()?.let { add("Screen" to it) }
            System.getProperty("os.version")?.substringBefore('-')?.let { add("Kernel" to it) }
            add("Up time" to InfoFormat.duration(SystemClock.elapsedRealtime()))
        }
        val system = "Android ${Build.VERSION.RELEASE}" + (skin?.let { " ($it)" } ?: "")
        return SettingsResponse.Facts("This is your $name running $system.", rows, setting.guide?.intentSpec(), "Open About phone")
    }

    private fun storageInfo(setting: KbSetting): SettingsResponse {
        val (total, free) = storage() ?: return SettingsResponse.Info("I couldn't read the storage on this phone.")
        val used = total - free
        val usedPercent = (used * 100 / total).toInt()
        val text = "${InfoFormat.size(free)} free of ${InfoFormat.size(total)}." +
            if (usedPercent >= FULL_STORAGE_PERCENT) " Your storage is almost full, which can slow the phone down." else ""
        val rows = listOf(
            "Used" to "${InfoFormat.size(used)} ($usedPercent%)",
            "Free" to InfoFormat.size(free),
            "Total" to InfoFormat.size(total),
        )
        return SettingsResponse.Facts(text, rows, setting.guide?.intentSpec(), "Free up space")
    }

    private fun batteryInfo(setting: KbSetting): SettingsResponse {
        // Sticky broadcast: passing no receiver just returns the latest battery state.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return SettingsResponse.Info("I couldn't read the battery.")
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
            battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val celsius = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, NO_VALUE).takeIf { it != NO_VALUE }?.div(10f)
        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            battery.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, NO_VALUE)
        } else NO_VALUE
        val state = when {
            status == BatteryManager.BATTERY_STATUS_FULL -> "Full"
            status == BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            plugged -> "Plugged in, not charging"
            else -> "Not charging"
        }
        val fullInMs = if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            context.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
        } else -1

        val rows = buildList {
            add("Level" to "$level%")
            add("Status" to state)
            HEALTH[battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)]?.let { add("Health" to it) }
            celsius?.let { add("Temperature" to InfoFormat.temperature(it)) }
            if (cycles > 0) add("Charge cycles" to cycles.toString())
            if (fullInMs > 0) add("Full in" to "about ${InfoFormat.duration(fullInMs)}")
        }
        val text = when (state) {
            "Full" -> "Battery is full."
            "Charging" -> "Battery is at $level% and charging."
            else -> "Battery is at $level%."
        } + if (celsius != null && celsius >= HOT_BATTERY_C) " It's running warm (${InfoFormat.temperature(celsius)})." else ""
        return SettingsResponse.Facts(text, rows, setting.guide?.intentSpec(), "Battery settings")
    }

    private fun softwareUpdate(setting: KbSetting): SettingsResponse {
        val patch = securityPatch()
        val text = buildString {
            if (patch != null) {
                val days = ChronoUnit.DAYS.between(patch, LocalDate.now())
                append("Your latest security update is from ${InfoFormat.date(patch)}, ${InfoFormat.ago(days)}. ")
                if (days > OLD_PATCH_DAYS) append("That's quite old, so it's worth checking for a new one. ")
            }
            setting.note?.let { append("$it ") }
            append("Tap Check for updates to open the phone's updater.")
        }
        val rows = buildList {
            add("Android version" to Build.VERSION.RELEASE)
            skinName()?.let { add("Software" to it) }
            add("Build number" to Build.DISPLAY)
            patch?.let { add("Security patch" to InfoFormat.date(it)) }
            playSystemUpdate()?.let { add("Google Play system update" to InfoFormat.date(it)) }
        }
        return SettingsResponse.Facts(text, rows, setting.guide?.intentSpec(), "Check for updates")
    }

    // ---- Readers ----

    /** Marketing name ("iQOO 15"). [Build.MODEL] is only the model code ("I2501"). */
    private fun deviceName(): String = NAME_PROPS.firstNotNullOfOrNull(::prop) ?: run {
        val brand = Build.BRAND.let { if (it == it.lowercase()) it.replaceFirstChar(Char::titlecase) else it }
        "$brand ${Build.MODEL}"
    }

    /** The maker's own OS on top of Android ("OriginOS 6"); null if we don't know this brand's property yet. */
    private fun skinName(): String? = SKIN_PROPS.firstNotNullOfOrNull(::prop)

    private fun securityPatch(): LocalDate? = runCatching { LocalDate.parse(Build.VERSION.SECURITY_PATCH) }.getOrNull()

    private fun chipset(): String {
        val maker = when (Build.SOC_MANUFACTURER) {
            "QTI" -> "Qualcomm"
            Build.UNKNOWN -> ""
            else -> Build.SOC_MANUFACTURER
        }
        val model = Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN } ?: Build.HARDWARE
        return "$maker $model".trim()
    }

    private fun memory() = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }

    /** (total, free) bytes of internal storage, as Settings shows them. Free includes cache the system can clear. */
    private fun storage(): Pair<Long, Long>? = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
    }.getOrNull()

    /** The panel's full resolution and top refresh rate, e.g. "1440 × 3168 · up to 144 Hz". */
    private fun screen(): String? {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val modes = display.supportedModes
        val largest = modes.maxByOrNull { it.physicalWidth * it.physicalHeight } ?: return null
        val hz = modes.maxOf { it.refreshRate }.roundToInt()
        return "${largest.physicalWidth} × ${largest.physicalHeight} · up to $hz Hz"
    }

    /** Settings shows this module's version ("2026-02-01S+") as the Play system update date. Needs the <queries> entry. */
    private fun playSystemUpdate(): LocalDate? = runCatching {
        LocalDate.parse(context.packageManager.getPackageInfo(MODULE_METADATA, 0).versionName!!.take(10))
    }.getOrNull()

    /** Brand-specific names aren't in [Build], only in system properties. Empty or unreadable → null. */
    private fun prop(name: String): String? = runCatching {
        ProcessBuilder("getprop", name).start().inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.ifEmpty { null }

    private companion object {
        // vivo/iQOO keys were checked on the iQOO 15. Add other brands' keys as we test on their phones.
        val NAME_PROPS = listOf("ro.vivo.product.release.name", "ro.product.marketname")
        val SKIN_PROPS = listOf("ro.vivo.os.build.display.id")

        const val MODULE_METADATA = "com.google.android.modulemetadata"
        const val FULL_STORAGE_PERCENT = 90
        const val OLD_PATCH_DAYS = 90
        const val HOT_BATTERY_C = 40f
        const val NO_VALUE = Int.MIN_VALUE

        val HEALTH = mapOf(
            BatteryManager.BATTERY_HEALTH_GOOD to "Good",
            BatteryManager.BATTERY_HEALTH_OVERHEAT to "Overheating",
            BatteryManager.BATTERY_HEALTH_COLD to "Too cold",
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE to "Over voltage",
            BatteryManager.BATTERY_HEALTH_DEAD to "Worn out",
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE to "Problem detected",
        )
    }
}

/** Pure formatting for [PhoneInfo], unit-tested on the JVM. */
object InfoFormat {

    fun date(date: LocalDate): String = date.format(DATE)

    /** Decimal units, like Android's own Settings (1 GB = 10^9 bytes). */
    fun size(bytes: Long): String {
        val gb = bytes / 1e9
        return when {
            gb >= 100 -> "${gb.roundToInt()} GB"
            gb >= 1 -> "${oneDecimal(gb)} GB"
            else -> "${(bytes / 1e6).roundToInt()} MB"
        }
    }

    /** Phones are sold with fixed RAM sizes; the OS reports a bit less because the kernel reserves some. */
    fun marketedRamGb(totalBytes: Long): Int {
        val gib = totalBytes / GIB
        return RAM_SIZES_GB.firstOrNull { it >= gib } ?: ceil(gib).toInt()
    }

    fun ago(days: Long): String = when {
        days <= 0 -> "today"
        days == 1L -> "yesterday"
        days < 14 -> "$days days ago"
        days < 31 -> "${days / 7} weeks ago"
        days < 365 -> "about ${plural((days / DAYS_PER_MONTH).roundToInt(), "month")} ago"
        else -> "over ${plural((days / 365).toInt(), "year")} ago"
    }

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${plural(days.toInt(), "day")} ${hours % 24} h"
            hours > 0 -> "$hours h ${minutes % 60} min"
            else -> "$minutes min"
        }
    }

    fun temperature(celsius: Float): String = "${oneDecimal(celsius.toDouble())} °C"

    private fun oneDecimal(x: Double) = String.format(Locale.ENGLISH, "%.1f", x).removeSuffix(".0")

    private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"

    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private const val GIB = 1024.0 * 1024 * 1024
    private const val DAYS_PER_MONTH = 30.44
    private val RAM_SIZES_GB = listOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32)
}
