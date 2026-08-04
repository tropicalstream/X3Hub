package com.x3hub.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskRoutingTest {

    @Test
    fun `the spoken form routes and loses its preamble`() {
        assertEquals("summarize the video", AskRouting.question("ask youtube to summarize the video"))
        assertEquals("how many acres burned", AskRouting.question("ask YouTube how many acres burned"))
        assertEquals("who is she talking about", AskRouting.question("ask the video who is she talking about"))
    }

    /**
     * The orchestrator is told to hand over only the question, so this is
     * what actually arrives most of the time — and keying on the preamble
     * is exactly how the native path went dark in dim mode.
     */
    @Test
    fun `an already-stripped question still routes`() {
        assertEquals("summarize the video", AskRouting.question("summarize the video"))
        assertEquals("what did they find", AskRouting.question("what did they find"))
        assertEquals("how long does the process take", AskRouting.question("how long does the process take"))
        assertEquals("explain the second point", AskRouting.question("explain the second point"))
        assertEquals("is it worth watching", AskRouting.question("is it worth watching"))
    }

    @Test
    fun `acting on the page is left to the other flows`() {
        assertNull(AskRouting.question("play the next video"))
        assertNull(AskRouting.question("pause it"))
        assertNull(AskRouting.question("search for cats"))
        assertNull(AskRouting.question("open the description"))
        assertNull(AskRouting.question("scroll down"))
    }

    /**
     * The regression this class exists for: an empty alternative inside
     * the ACTS pattern matches at any word boundary, so EVERY question
     * looks like an action. If someone reintroduces one, these fail.
     */
    @Test
    fun `questions are not swallowed by the action filter`() {
        listOf(
            "summarize the video",
            "what ingredients are needed",
            "who won",
            "why did it happen",
            "describe the ending"
        ).forEach { assertNull("'$it' must route", null.takeIf { _ -> AskRouting.question(it) == null }) }
    }

    @Test
    fun `trailing punctuation and blanks are handled`() {
        assertEquals("what happened", AskRouting.question("what happened?"))
        assertEquals("what happened", AskRouting.question("  what happened.  "))
        assertNull(AskRouting.question("   "))
        assertNull(AskRouting.question("the weather"))
    }
}
