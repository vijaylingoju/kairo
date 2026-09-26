package ai.kairo.gallery.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gemma's output is untrusted: only ids/actions that exist in the KB may get through. */
class SettingsPromptTest {

    private val kb = SettingsKb.parse(JSONObject(File("src/main/assets/settings_kb.json").readText()))

    private fun parse(json: String) = SettingsPrompt.parse(JSONObject(json), kb)

    @Test
    fun promptListsEverySettingAndTheQuery() {
        val prompt = SettingsPrompt.build(kb, "time 21:00, brightness 80%", "too loud")
        kb.settings.forEach { assertTrue(it.id, "${it.id} (${it.name}): " in prompt) }
        assertTrue(prompt.trimEnd().endsWith("User: too loud"))
        assertTrue("brightness 80%" in prompt)
    }

    @Test
    fun acceptsValidChange() {
        assertEquals(
            LlmDecision.Change("media_volume", "decrease"),
            parse("""{"type":"change","setting":"media_volume","action":"decrease"}"""),
        )
    }

    @Test
    fun rejectsUnknownSettingOrAction() {
        assertNull(parse("""{"type":"change","setting":"screen_saver","action":"on"}"""))
        assertNull(parse("""{"type":"change","setting":"flashlight","action":"increase"}"""))
        assertNull(parse("""{"type":"whatever"}"""))
    }

    @Test
    fun suggestionsDropInvalidItemsAndDuplicates() {
        val d = parse(
            """{"type":"suggest","message":"For your meeting:","items":[
                {"setting":"dnd","action":"on","reason":"Blocks calls"},
                {"setting":"dnd","action":"on","reason":"dup"},
                {"setting":"teleport","action":"on","reason":"nope"},
                {"setting":"vibrate_mode","action":"on","reason":"Feel calls"}]}""",
        ) as LlmDecision.Suggest
        assertEquals("For your meeting:", d.message)
        assertEquals(listOf("dnd", "vibrate_mode"), d.items.map { it.settingId })
    }

    @Test
    fun suggestionsWithNothingValidFallBack() {
        assertNull(parse("""{"type":"suggest","message":"x","items":[{"setting":"teleport","action":"on"}]}"""))
    }

    @Test
    fun noneKeepsMessage() {
        assertEquals(LlmDecision.None("Only settings, sorry."), parse("""{"type":"none","message":"Only settings, sorry."}"""))
    }
}
