package ai.kairo.gallery.settings

import android.content.Context
import android.util.Log
import ai.kairo.gallery.llm.Llm
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemma decides what to do; [KeywordSettingsAgent] executes it (and handles undo).
 * Falls back to keyword routing when the model isn't loaded, is busy, or returns something invalid.
 */
class LlmSettingsAgent(context: Context) : SettingsAgent {

    private val app = context.applicationContext
    private val keywords = KeywordSettingsAgent(app)

    val kb: SettingsKb get() = keywords.kb

    // Generation can't be interrupted, so it runs here and we stop *waiting* for it on timeout.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun handle(query: String): SettingsResponse {
        if (!Llm.isReady() || skipGemma(query)) return keywords.handle(query)
        val decision = ask(query) ?: return keywords.handle(query)
        // Gemma sometimes lists the checkup as one tip among generic ones; the checkup itself is the grounded answer.
        if (decision is LlmDecision.Suggest && decision.items.any { it.settingId == SettingIds.PHONE_CHECKUP }) {
            return keywords.apply(SettingIds.PHONE_CHECKUP, Actions.SHOW)
        }
        return when (decision) {
            is LlmDecision.Change -> keywords.apply(
                decision.settingId,
                decision.action,
                decision.value ?: keywords.valueFor(decision.settingId, query),
            )
            is LlmDecision.Suggest -> SettingsResponse.Suggestions(
                decision.message,
                decision.items.map { suggestion(it) },
            )
            // Trust the keywords if they found a setting the model missed.
            is LlmDecision.None -> keywords.handle(query).takeUnless { it is SettingsResponse.Info }
                ?: SettingsResponse.Info(decision.message)
        }
    }

    override suspend fun apply(settingId: String, action: String, value: String?) = keywords.apply(settingId, action, value)

    override suspend fun setWallpaper(photoUri: String, screen: String) = keywords.setWallpaper(photoUri, screen)

    override suspend fun undo(token: UndoToken) = keywords.undo(token)

    override fun close() {
        scope.cancel()
        keywords.close()
    }

    /**
     * Gemma takes 2–5 s with the full settings list in its prompt. Skip it when the keyword answer is as good:
     * - the problems the keyword agent checks against the real phone state (checkup, reading, eye strain), where
     *   Gemma only gives generic tips;
     * - requests the keywords fully understand ("turn off haptic feedback").
     */
    private fun skipGemma(query: String): Boolean {
        val match = keywords.kb.match(query)
        val reason = when {
            match?.id == SettingIds.PHONE_CHECKUP -> "checkup"
            QueryParser.isLowVision(query) -> "low vision"
            QueryParser.isEyeStrain(query) -> "eye strain"
            match != null && QueryParser.isSimpleRequest(query, match) -> "simple request for ${match.id}"
            else -> return false
        }
        Log.i(TAG, "\"$query\" → keywords ($reason), no Gemma")
        return true
    }

    private suspend fun ask(query: String): LlmDecision? {
        val prompt = SettingsPrompt.build(keywords.kb, keywords.phoneState(), query)
        val started = System.currentTimeMillis()
        val reply = withTimeoutOrNull(TIMEOUT_MS) {
            scope.async { runCatching { Llm.generate(app, Content.Text(prompt)) }.getOrNull() }.await()
        }
        val decision = reply?.let(Llm::extractJson)?.let { SettingsPrompt.parse(it, keywords.kb) }
        Log.i(TAG, "\"$query\" → $decision in ${System.currentTimeMillis() - started} ms (raw: ${reply?.take(300)})")
        return decision
    }

    private fun suggestion(item: LlmDecision.Suggest.Item): Suggestion {
        val setting = keywords.kb[item.settingId]!!
        val verb = when (item.action) {
            Actions.ON -> "Turn on"
            Actions.OFF -> "Turn off"
            Actions.INCREASE -> "Increase"
            Actions.DECREASE -> "Lower"
            Actions.SHOW -> "Check"
            else -> "Open"
        }
        return Suggestion(
            item.settingId, item.action,
            // Guide-only names already read as actions: "Close apps", not "Open Close apps".
            title = if (item.action == Actions.OPEN && setting.tier == Tier.GUIDE) setting.name else "$verb ${setting.name}",
            reason = item.reason.ifEmpty { setting.note.orEmpty() },
            buttonLabel = when (setting.tier) {
                Tier.DIRECT -> "Apply"
                Tier.PANEL -> "Open"
                Tier.GUIDE -> "Guide me"
                Tier.INFO -> "Show"
            },
        )
    }

    private companion object {
        const val TAG = "KairoSettingsLlm"

        /** Beyond this the user is better served by the instant keyword answer (e.g. the indexer holds the model). */
        const val TIMEOUT_MS = 10_000L
    }
}
