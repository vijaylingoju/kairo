package ai.kairo.gallery.ui

import android.content.Context
import android.content.Intent
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.search.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow

/** A place in the app that something outside it (the floating ball) wants to open. */
sealed interface OpenRequest {
    /** The conversation, to read an answer or keep asking. */
    data object KairoTab : OpenRequest

    /** "See all": the gallery search screen with this result. */
    data class Photos(val result: SearchResult) : OpenRequest

    data class Photo(val photos: List<IndexedImage>, val start: Int) : OpenRequest
}

/**
 * Opens the app at an [OpenRequest]. The request waits in [pending] until the gallery shows it: search results
 * don't fit in an Intent, and the app may not be running yet.
 */
object AppNavigation {
    val pending = MutableStateFlow<OpenRequest?>(null)

    fun open(context: Context, request: OpenRequest) {
        pending.value = request
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                // Reuse the running gallery (onNewIntent) instead of stacking a second one.
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }
}
