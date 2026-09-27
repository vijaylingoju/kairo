package ai.kairo.gallery.assistant

import ai.kairo.gallery.assistant.SmallTalk.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmallTalkTest {

    @Test
    fun kinds() {
        assertEquals(Kind.GREETING, SmallTalk.kind("Hey there"))
        assertEquals(Kind.HOW_ARE_YOU, SmallTalk.kind("hi kairo, how are you doing?"))
        assertEquals(Kind.THANKS, SmallTalk.kind("Thank you very much!"))
        assertEquals(Kind.BYE, SmallTalk.kind("good night"))
        assertEquals(Kind.ABOUT, SmallTalk.kind("hello, what can you do?"))
        assertEquals(Kind.OK, SmallTalk.kind("okkk"))
        assertEquals(Kind.THANKS, SmallTalk.kind("What’s up? thanks"))
    }

    /** A request, even one that starts with a greeting, is not small talk. */
    @Test
    fun requestsAreNot() {
        for (q in listOf("hi show my PAN card", "good morning images", "morning photos", "thanks for the bill", "సినిమా టికెట్లు", "", "a")) {
            assertNull(q, SmallTalk.kind(q))
        }
    }

    @Test
    fun dropsTheGreetingInFrontOfARequest() {
        assertEquals("show all images", SmallTalk.withoutGreeting("Hi Kairo, show all images"))
        assertEquals("what's my PNR?", SmallTalk.withoutGreeting("hello there what's my PNR?"))
        assertEquals("show all images", SmallTalk.withoutGreeting("show all images"))
        assertEquals("hi", SmallTalk.withoutGreeting("hi"))
        // Only plain hellos: these are searches.
        assertEquals("good morning images", SmallTalk.withoutGreeting("good morning images"))
        assertEquals("morning photos", SmallTalk.withoutGreeting("morning photos"))
    }
}
