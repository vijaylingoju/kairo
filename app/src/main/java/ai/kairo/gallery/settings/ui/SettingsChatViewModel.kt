package ai.kairo.gallery.settings.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.kairo.gallery.settings.IntentSpec
import ai.kairo.gallery.settings.KeywordSettingsAgent
import ai.kairo.gallery.settings.SettingsAgent
import ai.kairo.gallery.settings.SettingsResponse
import ai.kairo.gallery.settings.UndoToken
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
    val response: SettingsResponse? = null,
)

class SettingsChatViewModel(app: Application) : AndroidViewModel(app) {

    // Swap for the LLM-backed agent later; the UI doesn't change.
    private val agent: SettingsAgent = KeywordSettingsAgent(app)

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
        respond { agent.handle(query) }
    }

    fun apply(settingId: String, action: String) = respond { agent.apply(settingId, action) }

    fun undo(token: UndoToken) = respond { agent.undo(token) }

    private fun respond(block: suspend () -> SettingsResponse) {
        viewModelScope.launch {
            _busy.value = true
            val response = try {
                block()
            } catch (e: Exception) {
                SettingsResponse.Info("Something went wrong: ${e.message}")
            }
            _busy.value = false
            append(ChatMessage(nextId++, fromUser = false, text = response.text, response = response))
            if (response is SettingsResponse.OpenPanel) _launchRequests.tryEmit(response.open)
        }
    }

    private fun append(message: ChatMessage) = _messages.update { it + message }

    override fun onCleared() = agent.close()
}
