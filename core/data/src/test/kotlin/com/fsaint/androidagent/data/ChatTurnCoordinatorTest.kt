package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import com.fsaint.androidagent.runtime.*
import com.fsaint.androidagent.model.*
import com.fsaint.androidagent.policy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class ChatTurnCoordinatorTest {
    @Test fun unrelatedVoiceTurnKeepsPhotoHistoricalAndPreservesCurrentQuestion() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val store = ChatRepository(db)
            val chat = store.outside("owner")
            val seen = mutableListOf<ConversationRequest>()
            val turns = ChatTurnCoordinator(store, { ConversationImage("image/jpeg", byteArrayOf(9)) }, {}, {}, {
                seen += it
                ConversationResult("Done", ConversationTranscript(), 1, emptyList(), ConversationStopReason.FINAL_RESPONSE)
            })
            turns.run(request("photo", "Describe this photo", "CAPTURE"), chat.id, "saved-photo")
            assertNotNull(seen.last().image)
            assertTrue(seen.last().chatImages.isEmpty())
            turns.run(request("voice", "What is my battery level?"), chat.id)
            assertEquals("What is my battery level?", seen.last().userText)
            assertNull(seen.last().image)
            assertEquals(1, seen.last().chatImages.size)
            assertTrue(seen.last().chatImages.single().label.orEmpty().contains("Previously captured"))
            assertFalse(seen.last().chatImages.single().label.orEmpty().contains("default"))
            assertEquals(listOf("Describe this photo", "Done"), seen.last().priorMessages.map { it.text })
        } finally { db.close() }
    }
    @Test fun preparedCaptureIsAttachedBeforeAnalysisWithoutDuplicateUserInput() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val store = ChatRepository(db)
            val chat = store.outside("owner")
            store.begin("owner", chat.id, "r1", "Look", "CAPTURE")
            store.attachPhoto("owner", "r1", "photo")
            val turns = ChatTurnCoordinator(store, { ConversationImage("image/jpeg", byteArrayOf(9)) }, {}, {}, {
                assertNotNull(it.image)
                assertTrue(it.chatImages.isEmpty())
                assertTrue(it.priorMessages.isEmpty())
                ConversationResult("A cup", ConversationTranscript(), 1, emptyList(), ConversationStopReason.FINAL_RESPONSE)
            })
            turns.run(request("r1", "Look"), chat.id, "photo", preparedPhoto = true)
            assertEquals(listOf("Look", "A cup"), store.messages("owner", chat.id).map { it.text })
        } finally { db.close() }
    }
    private fun request(id: String, text: String, channel: String = "VOICE") = ConversationRequest(
        ScopeRegistry().sessionFor(Principal("owner", null, PrincipalRole.OWNER), channel),
        AgentEvent(id, "chat", "local", 0, emptyMap()), AgentContext(emptySet(), emptyMap()), text)

    @Test fun followupCarriesOriginalImageAndHistoryWithoutReusingToolBudget() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val store = ChatRepository(db)
            val chat = store.outside("owner")
            val seen = mutableListOf<ConversationRequest>()
            val turns = ChatTurnCoordinator(store, { ConversationImage("image/jpeg", byteArrayOf(9)) }, {}, {}, { req ->
                seen += req
                ConversationResult("A red cup", ConversationTranscript(), 1, emptyList(), ConversationStopReason.FINAL_RESPONSE)
            })
            turns.run(request("r1", "Look"), chat.id, "photo")
            turns.run(request("r2", "What color?"), chat.id)
            assertContentEquals(byteArrayOf(9), seen.last().chatImages.single().bytes)
            assertEquals(listOf("Look", "A red cup"), seen.last().priorMessages.map { it.text })
            assertEquals(0, seen.last().transcript.nextTurn)
            val other = store.create("owner", "Other")
            turns.run(request("r3", "Hello", "LOCAL_API"), other.id)
            assertTrue(seen.last().priorMessages.isEmpty())
            assertTrue(seen.last().chatImages.isEmpty())
        } finally { db.close() }
    }
    @Test fun cancelledAnalysisKeepsCapturedPhotoAndDuplicateRequestDoesNotRunAgain() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val store = ChatRepository(db)
            val chat = store.outside("owner")
            var calls = 0
            val turns = ChatTurnCoordinator(store, { ConversationImage("image/jpeg", byteArrayOf(9)) }, {}, {}, {
                calls++
                throw CancellationException("replaced")
            })
            assertFailsWith<CancellationException> { turns.run(request("r1", "Look"), chat.id, "photo") }
            assertEquals("INTERRUPTED", store.request("owner", "r1")!!.state)
            assertEquals("photo", store.messages("owner", chat.id).first().artifactId)
            turns.run(request("r1", "Look"), chat.id, "photo")
            assertEquals(1, calls)
        } finally { db.close() }
    }
}
