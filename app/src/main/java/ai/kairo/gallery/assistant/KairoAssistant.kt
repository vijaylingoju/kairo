package ai.kairo.gallery.assistant

import android.content.Context
import android.util.Log
import ai.kairo.gallery.llm.Llm
import ai.kairo.gallery.search.SearchEngine
import ai.kairo.gallery.search.SearchResult
import ai.kairo.gallery.settings.LlmSettingsAgent
import ai.kairo.gallery.settings.SettingsResponse
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Kairo's answer: photos, a settings reply, or a question back when it can't tell which one was meant. */
sealed interface AssistantReply {
    val text: String

    data class Photos(val result: SearchResult) : AssistantReply {
        override val text: String
            get() = result.answer ?: when (result.items.size) {
                0 -> "No photos match “${result.query}”."
                1 -> "Found 1 photo."
                else -> "Found ${result.items.size} photos."
            }
    }

    data class Settings(val response: SettingsResponse) : AssistantReply {
        override val text: String get() = response.text
    }

    /** Neither the rules nor Gemma could tell: the user picks with two buttons. */
    data class AskWhich(val query: String) : AssistantReply {
        override val text: String get() = "I can search your photos or help with your phone's settings. Which one?"
    }
}

/**
 * One entry point for the Kairo chat (and later the floating ball). It only decides which feature answers
 * ([IntentRouter], then Gemma); the gallery search and the settings agent themselves are unchanged.
 */
class KairoAssistant(context: Context) {

    private val app = context.applicationContext
    val settings = LlmSettingsAgent(app)

    // Generation can't be interrupted, so it runs here and we stop *waiting* for it on timeout.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun handle(query: String): AssistantReply {
        val rules = IntentRouter.route(query, settings.kb)
        if (rules != Route.UNSURE) {
            Log.i(TAG, "\"$query\" → $rules (rules)")
            return answer(query, rules)
        }
        return answer(query, askGemma(query) ?: Route.UNSURE)
    }

    /** Also used when the user picks after [AssistantReply.AskWhich]. */
    suspend fun answer(query: String, route: Route): AssistantReply = when (route) {
        Route.GALLERY -> AssistantReply.Photos(SearchEngine.search(app, query))
        Route.SETTINGS -> AssistantReply.Settings(settings.handle(query))
        Route.UNSURE -> AssistantReply.AskWhich(query)
    }

    private suspend fun askGemma(query: String): Route? {
        val started = System.currentTimeMillis()
        val reply = withTimeoutOrNull(TIMEOUT_MS) {
            scope.async {
                runCatching {
                    // Waits for the model if it is still loading (5–9 s after the app starts).
                    Llm.ensure(app)
                    Llm.generate(app, Content.Text(IntentRouter.prompt(query)), maxTokens = 16)
                }.getOrNull()
            }.await()
        }
        val route = reply?.let(Llm::extractJson)?.let(IntentRouter::parse)
        Log.i(TAG, "\"$query\" → $route (Gemma, ${System.currentTimeMillis() - started} ms, raw: ${reply?.take(80)})")
        return route
    }

    private companion object {
        const val TAG = "KairoAssistant"

        /** Covers loading the model; after that the user picks instead ([AssistantReply.AskWhich]). */
        const val TIMEOUT_MS = 15_000L
    }
}
