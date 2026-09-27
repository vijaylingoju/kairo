package ai.kairo.gallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.kairo.gallery.data.IndexDb
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.data.Prefs
import ai.kairo.gallery.embed.Clip
import ai.kairo.gallery.index.IndexScheduler
import ai.kairo.gallery.index.Indexer
import ai.kairo.gallery.llm.Llm
import ai.kairo.gallery.search.SearchEngine
import ai.kairo.gallery.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()

    val indexStatus: StateFlow<Indexer.Status> = Indexer.status
    val modelState: StateFlow<String> = Llm.state
    val clipState: StateFlow<String> = Clip.state

    private val _allImages = MutableStateFlow<List<IndexedImage>>(emptyList())
    val allImages: StateFlow<List<IndexedImage>> = _allImages

    private val _result = MutableStateFlow<SearchResult?>(null)
    val result: StateFlow<SearchResult?> = _result

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _watchScreenshots = MutableStateFlow(Prefs.screenshotsSince(app) > 0)
    val watchScreenshots: StateFlow<Boolean> = _watchScreenshots

    init {
        // Warm up the model in the background so the first search/index is fast.
        viewModelScope.launch(Dispatchers.IO) {
            if (Clip.isAvailable(ctx)) runCatching { Clip.embedTexts(ctx, listOf("warm up")) }
            runCatching { Llm.ensure(ctx) }
        }
        // Refresh the grid whenever an indexing run makes progress.
        viewModelScope.launch {
            Indexer.status.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _allImages.value = IndexDb.get(ctx).all()
        }
    }

    fun indexNow(force: Boolean) = IndexScheduler.runNow(ctx, force)

    /** Testing: stop indexing, wipe the index, then index everything again from scratch. */
    fun clearAllAndReindex() {
        viewModelScope.launch {
            IndexScheduler.cancelAll(ctx)
            Indexer.clearAll(ctx)
            _result.value = null
            _allImages.value = emptyList()
            IndexScheduler.scheduleWatch(ctx)
            IndexScheduler.runNow(ctx)
        }
    }

    fun setWatchScreenshots(on: Boolean) {
        Prefs.setWatchScreenshots(ctx, on)
        _watchScreenshots.value = on
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _result.value = null
            return
        }
        val q = query.trim()
        addRecent(q)
        _result.value = null
        viewModelScope.launch {
            _searching.value = true
            try {
                _result.value = SearchEngine.search(ctx, q)
            } finally {
                _searching.value = false
            }
        }
    }

    /** Shows a search that already ran elsewhere (the Kairo chat's "See all") without running it again. */
    fun showResult(result: SearchResult) {
        addRecent(result.query)
        _result.value = result
    }

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** Recent searches for the smart-search screen (this session only, never stored). */
    val recent: StateFlow<List<String>> = _recent

    private fun addRecent(q: String) {
        _recent.value = (listOf(q) + _recent.value.filter { !it.equals(q, ignoreCase = true) }).take(6)
    }

    fun clearRecent() {
        _recent.value = emptyList()
    }

    fun clearSearch() {
        _result.value = null
    }
}
