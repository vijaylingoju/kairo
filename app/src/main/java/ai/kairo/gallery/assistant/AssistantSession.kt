package ai.kairo.gallery.assistant

import android.content.Context
import ai.kairo.gallery.settings.IntentSpec
import ai.kairo.gallery.settings.SettingsResponse
import ai.kairo.gallery.settings.UndoToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val reply: AssistantReply? = null,
)

/**
 * The one conversation with Kairo, shared by every place that shows it (the Kairo tab now, the floating ball
 * later), so a question asked in one place continues in the other without asking Gemma again.
 * Kept in memory for the life of the app process; nothing is written to disk.
 */
class AssistantSession private constructor(context: Context) {

    private val assistant = KairoAssistant(context)

    // Not a ViewModel: an answer must still arrive after the screen that asked is gone.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Intents the screen should launch right away (e.g. the Wi-Fi panel). */
    private val _launchRequests = MutableSharedFlow<IntentSpec>(extraBufferCapacity = 1)
    val launchRequests = _launchRequests.asSharedFlow()

    private var nextId = 0L

    fun send(text: String) {
        val query = text.trim()
        if (query.isEmpty() || _busy.value) return
        append(ChatMessage(nextId++, fromUser = true, text = query))
        respond { assistant.handle(query) }
    }

    /** The user's answer to [AssistantReply.AskWhich]. */
    fun choose(query: String, route: Route) = respond { assistant.answer(query, route) }

    fun apply(settingId: String, action: String, value: String? = null) =
        respondSettings { assistant.settings.apply(settingId, action, value) }

    fun setWallpaper(photoUri: String, screen: String) = respondSettings { assistant.settings.setWallpaper(photoUri, screen) }

    fun undo(token: UndoToken) = respondSettings { assistant.settings.undo(token) }

    fun clear() {
        if (!_busy.value) _messages.value = emptyList()
    }

    private fun respondSettings(block: suspend () -> SettingsResponse) = respond { AssistantReply.Settings(block()) }

    private fun respond(block: suspend () -> AssistantReply) {
        scope.launch {
            _busy.value = true
            val reply = try {
                block()
            } catch (e: Exception) {
                AssistantReply.Settings(SettingsResponse.Info("Something went wrong: ${e.message}"))
            }
            _busy.value = false
            append(ChatMessage(nextId++, fromUser = false, text = reply.text, reply = reply))
            val response = (reply as? AssistantReply.Settings)?.response
            if (response is SettingsResponse.OpenPanel) _launchRequests.tryEmit(response.open)
        }
    }

    private fun append(message: ChatMessage) = _messages.update { it + message }

    companion object {
        @Volatile private var instance: AssistantSession? = null

        fun get(context: Context): AssistantSession =
            instance ?: synchronized(this) {
                instance ?: AssistantSession(context.applicationContext).also { instance = it }
            }
    }
}
