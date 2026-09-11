package com.fsaint.androidagent.runtime

import kotlin.test.*

class ChatContextTest {
    @Test fun recentPhotoSurvivesTextWindowAndEarlierSelectionTakesPriority() {
        val entries = (0..30).map { n -> ChatMessage("m$n", "r$n", n.toLong(), "user", "message$n", if (n == 0) "old" else if (n == 1) "latest" else null) }
        val result = ChatContext.select(entries, "old")
        assertEquals(20, result.messages.size)
        assertEquals("message11", result.messages.first().text)
        assertEquals(listOf("old", "latest"), result.artifactIds)
        assertTrue(result.omitted)
    }
    @Test fun textIsBoundedAndForeignSelectionRejected() {
        val entries = (0..4).map { ChatMessage("m$it", "r$it", it.toLong(), "user", "x".repeat(10000)) }
        assertTrue(ChatContext.select(entries).messages.sumOf { it.text.length } <= 24000)
        assertFailsWith<IllegalArgumentException> { ChatContext.select(entries, "foreign-photo") }
    }
}
