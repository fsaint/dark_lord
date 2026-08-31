package com.fsaint.androidagent.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class AgentDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AgentDatabase::class.java)

    @Test
    fun migrationFromVersion5RecreatesToolEffectsWithTheExactCurrentSchema() {
        val name = "agent-migration-${System.nanoTime()}"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO tool_effects (id, eventId, tool, state, replyText) VALUES ('effect-1', 'telegram:10', 'device.battery', 'COMPLETED', X'373225')")
            close()
        }

        helper.runMigrationsAndValidate(name, 6, true, AgentDatabase.MIGRATION_5_6).apply {
            query("SELECT payload, success, error, recoverable, verification FROM tool_effects WHERE id = 'effect-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("72%", cursor.getBlob(0).toString(Charsets.UTF_8))
                assertEquals(0, cursor.getInt(1))
                assertEquals(null, cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("UNVERIFIED", cursor.getString(4))
            }
            close()
        }
    }
}
