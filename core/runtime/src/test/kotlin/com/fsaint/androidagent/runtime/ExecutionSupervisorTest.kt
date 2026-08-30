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
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
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
        val catalog = ToolCatalog(provider)
        val calls = validated(catalog, (1..5).map { ToolCall("tool-$it") })

        val result = ExecutionSupervisor(catalog).execute(scope, calls)

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
        val catalog = ToolCatalog(provider)
        val calls = validated(catalog, listOf(ToolCall("a"), ToolCall("b")))

        val result = ExecutionSupervisor(catalog).execute(scope, calls)

        assertTrue(result.results.all { it.success })
        assertEquals(1, maximum)
    }

    @Test
    fun `tool timeout becomes a recoverable timeout result`() = runTest {
        val provider = fakeProvider {
            delay(100)
            ToolResult(true, "late")
        }
        val catalog = ToolCatalog(provider)
        val call = validated(catalog, listOf(ToolCall("slow"))).single()

        val result = ExecutionSupervisor(catalog).execute(scope, listOf(call))

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
        val catalog = ToolCatalog(provider)
        val supervisor = ExecutionSupervisor(catalog)
        val job = async {
            supervisor.execute(scope, validated(catalog, listOf(ToolCall("cancel"))))
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
        val catalog = ToolCatalog(provider)
        val call = validated(catalog, listOf(ToolCall("once"))).single()
        val key = ToolIdempotencyKey.forCall("run", 1, call.call)
        val supervisor = ExecutionSupervisor(catalog, InMemoryExecutionLedger())

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

    @Test
    fun `validated call cannot execute through a different scope`() = runTest {
        val executions = AtomicInteger(0)
        val provider = object : ToolProvider {
            override suspend fun discover(scope: ScopeSnapshot) = listOf(
                ToolDefinition("scoped", "", "{}", "test", "allowed", Confirmation.NONE, 1_000, "")
            )

            override suspend fun execute(call: ValidatedToolCall): ToolResult<Any> {
                executions.incrementAndGet()
                return ToolResult(true, "unexpected")
            }
        }
        val catalog = ToolCatalog(provider)
        val scopeA = ScopeSnapshot(session, setOf("allowed"), "same-id")
        val scopeB = ScopeSnapshot(session, setOf("other"), "same-id")
        val validated = catalog.validate(scopeA, ToolCall("scoped")).validated!!

        val result = ExecutionSupervisor(catalog).execute(scopeB, listOf(validated))

        assertEquals(ToolError.SCOPE_DENIED, result.results.single().error)
        assertEquals(0, executions.get())
    }

    @Test
    fun `two supervisors sharing a ledger execute a key only once`() = runTest {
        val executions = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val provider = fakeProvider {
            executions.incrementAndGet()
            started.complete(Unit)
            delay(100)
            ToolResult(true, "once")
        }
        val ledger = InMemoryExecutionLedger()
        val catalog = ToolCatalog(provider)
        val call = validated(catalog, listOf(ToolCall("once"))).single()
        val item = ExecutableToolCall(call, ToolIdempotencyKey.forCall("shared", 1, call.call))
        val first = async { ExecutionSupervisor(catalog, ledger).execute(scope, listOf(item)) }
        started.await()
        val second = async { ExecutionSupervisor(catalog, ledger).execute(scope, listOf(item)) }

        assertEquals("once", first.await().results.single().payload)
        assertEquals("once", second.await().results.single().payload)
        assertEquals(1, executions.get())
    }

    @Test
    fun `calls execute on the injected dispatcher`() = runTest {
        val threadNames = Collections.synchronizedList(mutableListOf<String>())
        val provider = fakeProvider {
            threadNames += Thread.currentThread().name
            ToolResult(true, "ok")
        }
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "execution-supervisor") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val catalog = ToolCatalog(provider)
            val call = validated(catalog, listOf(ToolCall("once"))).single()
            ExecutionSupervisor(catalog, dispatcher = dispatcher).execute(scope, listOf(call))
            assertTrue(threadNames.isNotEmpty())
            assertTrue(threadNames.all { it.startsWith("execution-supervisor") })
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `idempotency encoding separates control characters and field boundaries`() {
        val first = ToolIdempotencyKey.forCall("ab\u0000", 1, ToolCall("c"))
        val second = ToolIdempotencyKey.forCall("ab", 1, ToolCall("\u0000c"))
        val third = ToolIdempotencyKey.forCall("ab", 1, ToolCall("c", mapOf("x" to "\u0000")))

        assertNotEquals(first, second)
        assertNotEquals(second, third)
    }

    private fun validated(catalog: ToolCatalog, calls: List<ToolCall>): List<ValidatedToolCall> {
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
