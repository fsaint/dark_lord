package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PrincipalRepositoryTest {
    @Test
    fun knownPrincipalSurvivesRepositoryReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "principal-reopen.db"
        val principal = Principal("alice", "+14155550100", PrincipalRole.KNOWN)

        val database = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            PrincipalRepository(database.durableStateDao()).upsert(principal)
        } finally {
            database.close()
        }

        val reopenedDatabase = AgentDatabaseTestFactory.open(context, databaseName)
        try {
            val reopened = PrincipalRepository(reopenedDatabase.durableStateDao())
            assertEquals(principal, reopened.lookup("+14155550100"))
        } finally {
            reopenedDatabase.close()
        }
    }
}
