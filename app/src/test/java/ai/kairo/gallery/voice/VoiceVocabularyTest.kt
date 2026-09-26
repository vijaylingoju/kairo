package ai.kairo.gallery.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mis-hearings taken from the real voice test on the iQOO 15 (2026-09-27). */
class VoiceVocabularyTest {
    private val vocab = VoiceVocabulary(
        listOf(
            "Irumudi (UA16+)", "The Odyssey (A)", "Maa Inti Bangaaram", "Muralikrishna Cinema",
            "Sarathi Cinemas(Prasaditya...", "SURYA PALACE", "golden retriever", "mcdonalds", "ice cream",
        )
    )

    @Test
    fun soundAlikeTitleIsCorrected() {
        assertEquals("show me irumudi movie ticket", vocab.correct("show me hero modi movie ticket"))
    }

    @Test
    fun ordinarySpeechIsLeftAlone() {
        assertEquals("Show me all the movie tickets", vocab.correct("Show me all the movie tickets"))
        assertEquals("what's my PNR", vocab.correct("what's my PNR"))
        assertEquals("food photos from last month", vocab.correct("food photos from last month"))
    }

    @Test
    fun correctWordsStay() {
        assertEquals("irumudi ticket", vocab.correct("irumudi ticket"))
        assertEquals("golden retriever", vocab.correct("golden retriever"))
    }

    @Test
    fun venueSoundAlike() {
        assertEquals("tickets at muralikrishna", vocab.correct("tickets at murali krishna"))
    }

    @Test
    fun biasingPhrasesAreCleanTitles() {
        assertTrue("Irumudi" in vocab.phrases)
        assertTrue("Sarathi Cinemas" in vocab.phrases)
    }

    @Test
    fun soundKeys() {
        assertEquals(VoiceVocabulary.soundKey("heromodi"), VoiceVocabulary.soundKey("irumudi"))
    }
}
