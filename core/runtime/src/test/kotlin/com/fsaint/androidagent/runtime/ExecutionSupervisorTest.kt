package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.AgentEvent
import com.fsaint.androidagent.model.PrincipalRole
import com.fsaint.androidagent.model.ScopedAgentSession
import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExecutionSupervisorTest {
    private val session = ScopedAgentSession("session", "owner", PrincipalRole.OWNER, "owner", "test", "owner", 0)
    private val scope = ScopeSnapshot(session)

    @Test
    fun `at most four calls run and fifth is rejected`() = runTest {
        val started = AtomicInteger(0)
        val provider = fakeProvider { call ->
            started.incrementAndGet()
            delay(10)
            ToolResult(true, call.call.name as Any)
        }
        val calls = validated(provider, (1..5).map { ToolCall("tool-$it") })

        val result = ExecutionSupervisor(provider).execute(scope, calls)

        assertEquals(5, result.results.size)
        assertEquals(4, started.get())
        assertEquals(ToolError.DEVICE_BUSY, result.results[4].error)
        assertEquals(5, result.idempotencyKeys.size)
    }

    @Test
    fun `calls with same concurrency key are serialized`() = runTest {
        var active = 0
        var maximum = 0
        val lock = Any()
        val provider = fakeProvider { call ->
            synchronized(lock) {
                active++
                maximum = maxOf(maximum, active)
            }
            delay(20)
            synchronized(lock) { active-- }
            ToolResult(true, call.call.name as Any)
        }
        val calls = validated(provider, listOf(ToolCall("a"), ToolCall("b")))

        val result = ExecutionSupervisor(provider).execute(scope, calls)

        assertTrue(result.results.all { it.success })
        assertEquals(1, maximum)
    }

    @Test
    fun `tool timeout becomes a recoverable timeout result`() = runTest {
        val provider = fakeProvider {
            delay(100)
            ToolResult(true, "late")
        }
        val call = validated(provider, listOf(ToolCall("slow"))).single()

        val result = ExecutionSupervisor(provider).execute(scope, listOf(call))

        assertEquals(ToolError.TIMEOUT, result.results.single().error)
        assertTrue(result.results.single().recoverable)
    }

    @Test
    fun `parent cancellation propagates instead of becoming success`() = runTest {
        val started = CompletableDeferred<Unit>()
        val provider = fakeProvider {
            started.complete(Unit)
            delay(10_000)
            ToolResult(true, "unexpected")
        }
        val supervisor = ExecutionSupervisor(provider)
        val job = async {
            supervisor.execute(scope, validated(provider, listOf(ToolCall("cancel"))))
        }
        started.await()
        job.cancel()

        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun `duplicate idempotency keys execute only once and reuse result`() = runTest {
        val executions = AtomicInteger(0)
        val provider = fakeProvider {
            executions.incrementAndGet()
            ToolResult(true, "once")
        }
        val call = validated(provider, listOf(ToolCall("once"))).single()
        val key = ToolIdempotencyKey.forCall("run", 1, call.call)
        val supervisor = ExecutionSupervisor(provider, InMemoryExecutionLedger())

        val first = supervisor.execute(scope, listOf(ExecutableToolCall(call, key)))
        val second = supervisor.execute(scope, listOf(ExecutableToolCall(call, key)))

        assertEquals(1, executions.get())
        assertEquals(first.results.single(), second.results.single())
        assertEquals(key, first.idempotencyKeys.single())
    }

    @Test
    fun `idempotency key is stable and argument order independent`() {
        val first = ToolIdempotencyKey.forCall("run", 2, ToolCall("tool", mapOf("b" to "2", "a" to "1")))
        val second = ToolIdempotencyKey.forCall("run", 2, ToolCall("tool", mapOf("a" to "1", "b" to "2")))
        val different = ToolIdempotencyKey.forCall("run", 3, ToolCall("tool", mapOf("a" to "1", "b" to "2")))

        assertEquals(first, second)
        assertNotEquals(first, different)
    }

    private fun validated(provider: ToolProvider, calls: List<ToolCall>): List<ValidatedToolCall> {
        val catalog = ToolCatalog(provider)
        return calls.map { call ->
            kotlinx.coroutines.runBlocking { catalog.validate(scope, call).validated!! }
        }
    }

    private fun fakeProvider(execute: suspend (ValidatedToolCall) -> ToolResult<Any>): ToolProvider = object : ToolProvider {
        override suspend fun discover(scope: ScopeSnapshot): List<ToolDefinition> = listOf(
            ToolDefinition("tool-1", "", "{}", "test", null, Confirmation.NONE, 1_000, ""),
            ToolDefinition("tool-2", "", "{}", "test", null, Confirmation.NONE, 1_000, ""),
            ToolDefinition("tool-3", "", "{}", "test", null, Confirmation.NONE, 1_000, ""),
            ToolDefinition("tool-4", "", "{}", "test", null, Confirmation.NONE, 1_000, ""),
            ToolDefinition("tool-5", "", "{}", "test", null, Confirmation.NONE, 1_000, ""),
            ToolDefinition("a", "", "{}", "test", null, Confirmation.NONE, 1_000, "same-resource"),
            ToolDefinition("b", "", "{}", "test", null, Confirmation.NONE, 1_000, "same-resource"),
            ToolDefinition("slow", "", "{}", "test", null, Confirmation.NONE, 10, ""),
            ToolDefinition("cancel", "", "{}", "test", null, Confirmation.NONE, 1_000, ""),
            ToolDefinition("once", "", "{}", "test", null, Confirmation.NONE, 1_000, ""),
        )

        override suspend fun execute(call: ValidatedToolCall): ToolResult<Any> = execute(call)
    }
}
