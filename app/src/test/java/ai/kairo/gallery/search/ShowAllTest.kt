package ai.kairo.gallery.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** "show all images" is every stopword at once: it must mean the whole gallery, not an empty filter. */
class ShowAllTest {

    @Test
    fun wholeGallery() {
        val all = listOf("show all images", "Show me all my photos", "all pictures", "photos", "open my gallery", "can you show me every pic?")
        assertEquals(emptyList<String>(), all.filterNot(RuleParser::isShowAll))
    }

    @Test
    fun stillASearch() {
        val searches = listOf("show all images from today", "all screenshots", "all dog photos", "show all", "movie tickets", "", "సినిమా ఫోటోలు")
        assertEquals(emptyList<String>(), searches.filter(RuleParser::isShowAll))
    }
}
