package ai.kairo.gallery.search

import ai.kairo.gallery.data.IndexedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases taken from the 579-photo evaluation on the iQOO 15 (2026-09-27). */
class ObjectMatchTest {

    private fun photo(objects: List<String>, description: String = "", tags: List<String> = emptyList(), category: String = "other") =
        IndexedImage(
            mediaId = 1, uri = "", name = "", folder = "", dateTaken = 0, dateModified = 0, lat = null, lng = null,
            category = category, description = description, tags = tags, fields = emptyMap(), ocrText = "",
            status = "done", error = null, indexMs = 0, objects = objects,
        )

    private fun match(query: String, img: IndexedImage) = ObjectMatch.matches(img, ObjectMatch.groups(query))

    @Test
    fun colourBindsToTheWordAfterIt() {
        assertEquals(listOf(listOf("red", "bicycle")), ObjectMatch.groups("red bicycle"))
        assertEquals(listOf(listOf("snowy", "mountains")), ObjectMatch.groups("show me photos of snowy mountains"))
        assertEquals(listOf(listOf("people"), listOf("beach")), ObjectMatch.groups("people at the beach"))
    }

    @Test
    fun colourMustBeOnTheSameObject() {
        // The photo plain word search got wrong: both words are there, but the bicycle is blue.
        assertFalse(match("red bicycle", photo(listOf("blue bicycle", "red handlebar wrap", "leather saddle"))))
        assertTrue(match("red bicycle", photo(listOf("red road bicycle", "carbon wheels"))))
        assertTrue(match("red bicycle", photo(listOf("man in brown hoodie", "red mini bicycle"))))
    }

    @Test
    fun pluralsAndLongPrefixesMatch() {
        assertTrue(match("a cat", photo(listOf("two tabby cats", "blue couch"))))
        assertTrue(match("a hippo", photo(listOf("hippopotamus", "muddy water"))))
        assertFalse(match("a car", photo(listOf("orange carrot"))))  // short words match whole only
    }

    @Test
    fun looseTagsDoNotCount() {
        // Tigers are tagged "big cat"; a search for a cat means a cat.
        assertFalse(match("a cat", photo(listOf("orange striped tigers", "snowy rocks"), tags = listOf("tiger", "big cat"))))
    }

    @Test
    fun imitationsOnlyWhenAskedFor() {
        val plush = photo(listOf("pink and white plush toy", "white and black dog plush toy"), description = "Stuffed animals including a dog")
        assertFalse(match("a dog", plush))
        assertTrue(match("dog toy", plush))
        // Posters are not imitations: the hackathon poster is what "iqoo hackathon" is looking for.
        assertTrue(match("iqoo hackathon", photo(listOf("iqoo hackathon poster", "yellow card"))))
    }

    @Test
    fun peopleWordsMatchAnyPerson() {
        assertTrue(match("people at the beach", photo(listOf("young man", "sandy beach", "blue ocean"))))
        assertFalse(match("people at the beach", photo(listOf("sandy beach", "blue ocean"))))
    }

    @Test
    fun descriptionStandsInUntilPhotoIsReindexed() {
        assertTrue(match("a dog", photo(emptyList(), description = "A black dog running on a wet beach")))
    }

    @Test
    fun categoryWordMatchesCategory() {
        assertTrue(match("food", photo(listOf("masala dosa", "steel plate"), category = "food")))
    }
}
