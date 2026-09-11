package com.fsaint.androidagent

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Run only on a disposable emulator; provisions a fixture owner there. */
class ChatsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun namedChatsOutsideArchiveAndSettingsNavigation() {
        assumeTrue("This fixture test must never mutate a physical phone", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DarkLordApplication
        val title = "Travel ${System.currentTimeMillis()}"
        runBlocking {
            if (app.principals.owner() == null) app.principals.provisionInitialOwner("+15555550123")
            app.outsideChat()
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Outside chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Outside chat")[0].performClick()
            compose.onNodeWithText("Double press adds a photo. Long press talks about it.").assertExists()
            compose.onNodeWithText("New Outside chat").performClick()
            compose.onNodeWithText("Back").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Archived · Read only").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("New chat").performClick()
            compose.onNodeWithText("Chat name").performTextInput(title)
            compose.onNodeWithText("Save").performClick()
            // The title also appears in the name editor; wait for the detail composer instead.
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Message").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Message").performTextInput("Keep this draft")
            runBlocking {
                val owner = requireNotNull(app.principals.owner())
                val chat = app.chats.list(owner.id).single { it.title == title }
                app.chats.begin(owner.id, chat.id, "fixture-${chat.id}", "Remember the blue suitcase", "LOCAL_API")
                app.chats.finish(owner.id, "fixture-${chat.id}", "The suitcase is blue.", "COMPLETE")
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("The suitcase is blue.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Owner setup").assertExists()
            compose.onNodeWithText("Chats").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Message").assertExists()
            compose.onNodeWithText("Keep this draft").assertExists()
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("The suitcase is blue.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Keep this draft").assertExists()
        }
    }
}
