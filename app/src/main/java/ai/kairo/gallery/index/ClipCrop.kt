package ai.kairo.gallery.index

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Prepares a bitmap for CLIP so the model sees the content, not the chrome.
 * CLIP center-crops to a square; on a tall screenshot with black bars or a status bar that square is
 * mostly empty UI, which makes such screenshots score "sort of similar" to every query.
 */
object ClipCrop {
    private const val SCREENSHOT_TOP = 0.04f     // status bar
    private const val SCREENSHOT_BOTTOM = 0.05f  // gesture / nav bar
    private const val FLAT_STDDEV = 10.0         // luminance std-dev below this = empty row/column
    private const val MAX_TRIM = 0.35f           // never trim more than this from any one side

    fun forClip(src: Bitmap, screenshot: Boolean): Bitmap {
        var bmp = src
        if (screenshot) {
            val top = (bmp.height * SCREENSHOT_TOP).toInt()
            val bottom = (bmp.height * SCREENSHOT_BOTTOM).toInt()
            bmp = Bitmap.createBitmap(bmp, 0, top, bmp.width, bmp.height - top - bottom)
        }
        return trimFlatBorders(bmp)
    }

    /** Removes uniform (letterbox / blank UI) rows and columns from every edge. */
    fun trimFlatBorders(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return bmp
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun lum(p: Int) = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        fun rowFlat(y: Int): Boolean = flat(w) { x -> lum(px[y * w + x]) }
        fun colFlat(x: Int): Boolean = flat(h) { y -> lum(px[y * w + x]) }

        val maxY = (h * MAX_TRIM).toInt()
        val maxX = (w * MAX_TRIM).toInt()
        var top = 0
        while (top < maxY && rowFlat(top)) top++
        var bottom = 0
        while (bottom < maxY && rowFlat(h - 1 - bottom)) bottom++
        var left = 0
        while (left < maxX && colFlat(left)) left++
        var right = 0
        while (right < maxX && colFlat(w - 1 - right)) right++

        if (top + bottom + left + right == 0) return bmp
        return Bitmap.createBitmap(bmp, left, top, w - left - right, h - top - bottom)
    }

    private inline fun flat(n: Int, value: (Int) -> Double): Boolean {
        var sum = 0.0
        var sq = 0.0
        var count = 0
        var i = 0
        while (i < n) {
            val v = value(i)
            sum += v; sq += v * v; count++
            i += 2
        }
        val mean = sum / count
        return sqrt(maxOf(0.0, sq / count - mean * mean)) < FLAT_STDDEV
    }
}
