package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {
    @Test fun supersedingPhotoAdmitsBeforeOldCleanupAndLateCompletionCannotOverwriteIt() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repo = ChatRepository(db)
            val chat = repo.outside("owner")
            repo.begin("owner", chat.id, "old", "Look", "CAPTURE")
            // The old capture/model has not acknowledged cancellation or entered its finally block.
            repo.begin("owner", chat.id, "new", "Look again", "CAPTURE", supersede = true)
            assertEquals("INTERRUPTED", repo.request("owner", "old")!!.state)
            assertEquals("RUNNING", repo.request("owner", "new")!!.state)
            assertTrue(repo.messages("owner", chat.id).any { it.text.contains("No new photo was saved") })
            repo.finish("owner", "old", "Stale answer", "COMPLETE")
            repo.recordTool("owner", "old", "0", "Previously dispatched action completed")
            assertFalse(repo.messages("owner", chat.id).any { it.text == "Stale answer" })
            assertTrue(repo.messages("owner", chat.id).any { it.role == "tool" })
        } finally { db.close() }
    }

    @Test fun supersessionDuringAttachmentTransactionRollsBackTheLatePhoto() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repo = ChatRepository(db)
            val chat = repo.outside("owner")
            repo.begin("owner", chat.id, "old", "Look", "CAPTURE")
            // Supersede after the transaction's reads, then after its insert, before commit.
            for (invalidAt in listOf(2, 3)) {
                var checks = 0
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    repo.attachPhoto("owner", "old", "late-photo") { ++checks < invalidAt }
                }
                assertEquals(invalidAt, checks)
                assertNull(repo.messages("owner", chat.id).single().artifactId)
            }
        } finally { db.close() }
    }

    @Test fun staleSupersedingAdmissionRollsBackWithoutInterruptingTheCurrentRequest() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repo = ChatRepository(db)
            val chat = repo.outside("owner")
            repo.begin("owner", chat.id, "current", "Look", "CAPTURE")
            var checks = 0
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                repo.begin("owner", chat.id, "stale", "Look again", "CAPTURE", supersede = true) { ++checks < 3 }
            }
            assertEquals("RUNNING", repo.request("owner", "current")!!.state)
            assertNull(repo.request("owner", "stale"))
            assertEquals(1, repo.messages("owner", chat.id).size)
        } finally { db.close() }
    }

    @Test fun toolCallbacksHaveStableIdentityAndInterruptedOutcomesReachContext() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repo = ChatRepository(db)
            val chat = repo.outside("owner")
            repo.begin("owner", chat.id, "r1", "Send it", "VOICE")
            repo.recordTool("owner", "r1", "0", "sms.send success=true")
            repo.recordTool("owner", "r1", "0", "duplicate")
            repo.finish("owner", "r1", "", "INTERRUPTED")
            val entries = repo.messages("owner", chat.id)
            assertEquals(1, entries.count { it.role == "tool" })
            val context = com.fsaint.androidagent.runtime.ChatContext.select(entries)
            assertTrue(context.messages.any { it.text.contains("sms.send success=true") })
        } finally { db.close() }
    }
    @Test fun outsideReplacementArchivesAndDoesNotMixOwners() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repo = ChatRepository(db)
            val old = repo.outside("owner")
            assertEquals(old.id, repo.outside("owner").id)
            val fresh = repo.newOutside("owner")
            assertNotEquals(old.id, fresh.id)
            assertTrue(repo.list("owner").first { it.id == old.id }.archived)
            assertFailsWith<IllegalArgumentException> { repo.messages("stranger", old.id) }
            assertTrue(repo.list("stranger").isEmpty())
        } finally { db.close() }
    }

    @Test fun duplicateCallbacksDoNotAppendAgainAndRecoveryKeepsPhoto() = runTest {
        val db = AgentDatabaseTestFactory.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val repo = ChatRepository(db)
            val chat = repo.outside("owner")
            assertTrue(repo.begin("owner", chat.id, "r1", "Look", "CAPTURE", "photo1"))
            assertFalse(repo.begin("owner", chat.id, "r1", "Look", "CAPTURE", "photo1"))
            repo.finish("owner", "r1", "A cup", "COMPLETE")
            repo.finish("owner", "r1", "Duplicate", "COMPLETE")
            assertEquals(listOf("Look", "A cup"), repo.messages("owner", chat.id).map { it.text })
            repo.begin("owner", chat.id, "r2", "Which color?", "VOICE")
            repo.recover()
            assertEquals("INTERRUPTED", repo.request("owner", "r2")!!.state)
            assertEquals("photo1", repo.messages("owner", chat.id).first().artifactId)
            assertEquals(listOf(0L, 1L, 2L, 3L), repo.messages("owner", chat.id).map { it.sequence })
            assertTrue(repo.messages("owner", chat.id).last().text.contains("unknown completion"))
        } finally { db.close() }
    }

    @Test fun messagesAndOutsidePointerSurviveReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "chat-${System.nanoTime()}.db"
        var db = AgentDatabaseTestFactory.open(context, name)
        try {
            val first = ChatRepository(db)
            val chat = first.outside("owner")
            first.begin("owner", chat.id, "r1", "Remember this", "LOCAL", "photo")
            db.close()
            db = AgentDatabaseTestFactory.open(context, name)
            val second = ChatRepository(db)
            assertEquals(chat.id, second.outside("owner").id)
            assertEquals("photo", second.messages("owner", chat.id).single().artifactId)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
