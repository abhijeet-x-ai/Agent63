package com.devstation.android.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseLineParserTest {

    @Test
    fun `parses simple data event`() {
        val parser = SseLineParser()
        assertNull(parser.feed("data: {\"a\":1}"))
        val event = parser.feed("")
        assertEquals("{\"a\":1}", event?.data)
        assertNull(event?.eventName)
        assertFalse(event!!.isDoneSentinel)
    }

    @Test
    fun `parses event name and data`() {
        val parser = SseLineParser()
        parser.feed("event: content_block_delta")
        parser.feed("data: {\"type\":\"text_delta\"}")
        val event = parser.feed("")
        assertEquals("content_block_delta", event?.eventName)
        assertEquals("{\"type\":\"text_delta\"}", event?.data)
    }

    @Test
    fun `handles multiline data joined with newline`() {
        val parser = SseLineParser()
        parser.feed("data: first")
        parser.feed("data: second")
        val event = parser.feed("")
        assertEquals("first\nsecond", event?.data)
    }

    @Test
    fun `detects DONE sentinel`() {
        val parser = SseLineParser()
        parser.feed("data: [DONE]")
        val event = parser.feed("")
        assertTrue(event!!.isDoneSentinel)
    }

    @Test
    fun `strips CR from CRLF streams`() {
        val parser = SseLineParser()
        parser.feed("data: hello\r")
        val event = parser.feed("\r")
        assertEquals("hello", event?.data)
    }

    @Test
    fun `ignores comment lines without closing event`() {
        val parser = SseLineParser()
        parser.feed(": keepalive")
        assertNull(parser.feed(""))
        parser.feed("data: x")
        assertEquals("x", parser.feed("")?.data)
    }

    @Test
    fun `blank lines without fields produce no events`() {
        val parser = SseLineParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed(""))
    }

    @Test
    fun `reset clears partial state`() {
        val parser = SseLineParser()
        parser.feed("data: partial")
        parser.reset()
        assertNull(parser.feed(""))
    }
}
