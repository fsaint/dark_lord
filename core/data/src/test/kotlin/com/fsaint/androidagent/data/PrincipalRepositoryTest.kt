package com.fsaint.androidagent.data

import androidx.test.core.app.ApplicationProvider
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.policy.Principal
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun ownerAndKnownPrincipalCannotShareAnE164Number() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = AgentDatabaseTestFactory.inMemory(context)
        try {
            val repository = PrincipalRepository(database.durableStateDao())
            repository.upsert(Principal("owner", "+14155550100", PrincipalRole.OWNER))

            assertFailsWith<IllegalArgumentException> {
                repository.upsert(Principal("known:+14155550100", "+14155550100", PrincipalRole.KNOWN))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun unnormalizableSourceIsNotRewrittenByAndroidFreeRepository() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = AgentDatabaseTestFactory.inMemory(context)
        try {
            val principal = PrincipalRepository(database.durableStateDao()).lookup("private-number")

            assertEquals(Principal("unknown:private-number", "private-number", PrincipalRole.UNKNOWN), principal)
        } finally {
            database.close()
        }
    }
}
