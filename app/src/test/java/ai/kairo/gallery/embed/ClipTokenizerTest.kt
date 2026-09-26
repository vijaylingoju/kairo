package ai.kairo.gallery.embed

import ai.kairo.gallery.search.RuleParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ClipTokenizerTest {
    private val tok = ClipTokenizer.fromStream(File("src/main/assets/clip_bpe_vocab.txt").inputStream())

    private fun ids(text: String): IntArray {
        val t = tok.tokenize(text)
        return t.copyOf(t.indexOf(ClipTokenizer.EOT) + 1)
    }

    @Test
    fun matchesReferenceClipTokenize() {
        // clip.tokenize("a photo of a dog") from openai/CLIP
        assertArrayEquals(intArrayOf(49406, 320, 1125, 539, 320, 1929, 49407), ids("a photo of a dog"))
        assertArrayEquals(intArrayOf(49406, 320, 2368, 49407), ids("A  cat"))
    }

    @Test
    fun padsAndTruncatesTo77() {
        assertEquals(77, tok.tokenize("dog").size)
        val long = tok.tokenize("dog ".repeat(200))
        assertEquals(77, long.size)
        assertEquals(ClipTokenizer.EOT, long[76])
    }

    @Test
    fun rareWordsSplitIntoKnownPieces() {
        val t = ids("golden retriever on the beach")
        assert(t.size > 6) { t.joinToString() }
    }

    @Test
    fun visualPhraseStripsCommandWords() {
        assertEquals("my dog at the beach", RuleParser.visualPhrase("Show me photos of my dog at the beach today"))
        assertEquals("golden retriever dog", RuleParser.visualPhrase("golden retriever dog image"))
        assertEquals("sunset at the lake with my dog", RuleParser.visualPhrase("the sunset at the lake with my dog"))
    }
}
