package ai.kairo.gallery.settings

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import ai.kairo.gallery.search.SearchEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Wallpaper from the gallery: find photos with Kairo's own search, then set the one the user taps. */
class Wallpapers(private val context: Context) {

    private val manager = WallpaperManager.getInstance(context)

    /** Up to [MAX_CHOICES] photos matching [photo]. Nothing changes until the user picks one. */
    suspend fun choose(setting: KbSetting, screen: String, photo: String?): SettingsResponse {
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) {
            return SettingsResponse.Info("This phone doesn't let apps change the wallpaper.")
        }
        if (photo.isNullOrBlank()) {
            return SettingsResponse.Guide(
                "Tell me which photo, like \"set my dog photo as wallpaper\", or pick one yourself:",
                setting.guide?.steps.orEmpty(),
                setting.guide?.intentSpec(),
            )
        }
        // The request is already reduced to a photo description, so search skips its own Gemma pass (~3 s).
        val found = SearchEngine.search(context, photo, useLlm = false).items.take(MAX_CHOICES)
        if (found.isEmpty()) {
            return SettingsResponse.Info("I couldn't find a photo of \"$photo\" in your gallery. Try describing it another way.")
        }
        val pick = if (found.size == 1) "Found it. Tap the photo" else "Here are the best matches for \"$photo\". Tap one"
        return SettingsResponse.ChoosePhoto(
            // Android doesn't let apps read the current wallpaper, so there's nothing to restore on undo.
            "$pick to make it your ${where(screen)} wallpaper. This can't be undone from Kairo.",
            found.map { it.uri },
            screen,
        )
    }

    /** Throws if the photo can't be read or the phone refuses the wallpaper. */
    fun set(photoUri: String, screen: String): SettingsResponse {
        val (screenWidth, screenHeight) = screenSize()
        val bitmap = decode(Uri.parse(photoUri), screenWidth, screenHeight)
        val crop = centerCrop(bitmap.width, bitmap.height, screenWidth, screenHeight)
        // One call per screen: on the iQOO, FLAG_SYSTEM | FLAG_LOCK in one call left vivo's live lock-screen wallpaper in place.
        flags(screen).forEach { manager.setBitmap(bitmap, crop, true, it) }
        bitmap.recycle()
        return SettingsResponse.Done(
            "Done! That photo is now your ${where(screen)} wallpaper. You can change it back in your wallpaper settings.",
            undo = null,
        )
    }

    /**
     * ImageDecoder applies the EXIF rotation, so camera photos don't end up sideways. Shrinks by powers of 2
     * (the shape never changes) while the photo still covers the screen.
     */
    private fun decode(uri: Uri, screenWidth: Int, screenHeight: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val short = min(info.size.width, info.size.height)
            val long = max(info.size.width, info.size.height)
            var sample = 1
            while (short / (sample * 2) >= screenWidth && long / (sample * 2) >= screenHeight) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** The middle of the photo in the screen's shape; that's the part the phone shows. */
    private fun centerCrop(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Rect {
        val screenRatio = screenWidth.toFloat() / screenHeight
        return if (width.toFloat() / height > screenRatio) {
            val cropWidth = (height * screenRatio).roundToInt()
            Rect((width - cropWidth) / 2, 0, (width + cropWidth) / 2, height)
        } else {
            val cropHeight = (width / screenRatio).roundToInt()
            Rect(0, (height - cropHeight) / 2, width, (height + cropHeight) / 2)
        }
    }

    /** Portrait (width, height) of the current display mode. */
    private fun screenSize(): Pair<Int, Int> {
        val mode = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        return min(mode.physicalWidth, mode.physicalHeight) to max(mode.physicalWidth, mode.physicalHeight)
    }

    private fun flags(screen: String) = when (screen) {
        Actions.HOME -> listOf(WallpaperManager.FLAG_SYSTEM)
        Actions.LOCK -> listOf(WallpaperManager.FLAG_LOCK)
        else -> listOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK)
    }

    private fun where(screen: String) = when (screen) {
        Actions.HOME -> "home screen"
        Actions.LOCK -> "lock screen"
        else -> "home and lock screen"
    }

    private companion object {
        const val MAX_CHOICES = 3
    }
}
