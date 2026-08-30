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
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class PrincipalRepositoryTest {
    @Test
    fun provisionInitialOwnerCreatesAndReturnsPersistedOwner() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = AgentDatabaseTestFactory.inMemory(context)
        try {
            val repository = PrincipalRepository(database.durableStateDao())

            val created = repository.provisionInitialOwner("+14155550100")

            assertEquals("+14155550100", created.e164)
            assertEquals(PrincipalRole.OWNER, created.role)
            assertEquals(created, repository.owner())
        } finally {
            database.close()
        }
    }

    @Test
    fun provisionInitialOwnerRejectsSecondOwner() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = AgentDatabaseTestFactory.inMemory(context)
        try {
            val repository = PrincipalRepository(database.durableStateDao())
            val original = repository.provisionInitialOwner("+14155550100")

            assertFailsWith<IllegalArgumentException> {
                repository.provisionInitialOwner("+14155550101")
            }
            assertEquals(original, repository.owner())
        } finally {
            database.close()
        }
    }

    @Test
    fun provisionInitialOwnerRejectsInvalidE164Numbers() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = AgentDatabaseTestFactory.inMemory(context)
        try {
            val repository = PrincipalRepository(database.durableStateDao())

            listOf("", "14155550100", "+0123456789", "+1 415 555 0100", "+1234567890123456").forEach { invalid ->
                assertFailsWith<IllegalArgumentException>("Expected $invalid to be rejected") {
                    repository.provisionInitialOwner(invalid)
                }
            }
            assertEquals(null, repository.owner())
        } finally {
            database.close()
        }
    }

    @Test
    fun provisionInitialOwnerRejectsE164AssignedToKnownPrincipal() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = AgentDatabaseTestFactory.inMemory(context)
        try {
            val repository = PrincipalRepository(database.durableStateDao())
            repository.upsert(Principal("known:+14155550100", "+14155550100", PrincipalRole.KNOWN))

            assertFailsWith<IllegalArgumentException> {
                repository.provisionInitialOwner("+14155550100")
            }
            assertEquals(null, repository.owner())
        } finally {
            database.close()
        }
    }

    @Test
    fun provisionedInitialOwnerSurvivesRepositoryReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "initial-owner-${System.nanoTime()}.db"
        var created: Principal? = null
        try {
            val database = AgentDatabaseTestFactory.open(context, databaseName)
            try {
                created = PrincipalRepository(database.durableStateDao()).provisionInitialOwner("+14155550100")
            } finally {
                database.close()
            }

            val reopenedDatabase = AgentDatabaseTestFactory.open(context, databaseName)
            try {
                val reopenedOwner = PrincipalRepository(reopenedDatabase.durableStateDao()).owner()
                assertEquals(assertNotNull(created), reopenedOwner)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

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
