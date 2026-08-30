package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.ToolCall
import com.fsaint.androidagent.model.ToolError
import com.fsaint.androidagent.model.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.security.MessageDigest

/** Durable boundary used to make completed tool calls safe to resume. */
interface ExecutionLedger {
    suspend fun lookup(idempotencyKey: String): ToolResult<Any>?
    suspend fun record(idempotencyKey: String, result: ToolResult<Any>)
}

/** Simple ledger for runtime tests and process-local callers. */
class InMemoryExecutionLedger : ExecutionLedger {
    private val mutex = Mutex()
    private val results = mutableMapOf<String, ToolResult<Any>>()

    override suspend fun lookup(idempotencyKey: String): ToolResult<Any>? = mutex.withLock {
        results[idempotencyKey]
    }

    override suspend fun record(idempotencyKey: String, result: ToolResult<Any>) {
        mutex.withLock {
            results.putIfAbsent(idempotencyKey, result)
        }
    }
}

/** Stable, non-secret idempotency key for a run/turn/tool/arguments tuple. */
object ToolIdempotencyKey {
    fun forCall(runId: String, turn: Int, call: ToolCall): String {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(turn >= 0) { "turn must not be negative" }
        val arguments = call.arguments.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }
        val canonical = "$runId\u0000$turn\u0000${call.name}\u0000$arguments"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "tool-v1:$digest"
    }

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace("&", "%26")
        .replace("=", "%3D")
}

data class ExecutionBatchResult(
    val results: List<ToolResult<Any>>,
    val cancelled: Boolean,
    val idempotencyKeys: List<String> = emptyList(),
)

/** A validated call with an explicit durable identity. */
data class ExecutableToolCall(
    val call: ValidatedToolCall,
    val idempotencyKey: String,
) {
    init { require(idempotencyKey.isNotBlank()) }
}

/**
 * Runs validated calls with bounded parallelism and per-resource serialization.
 *
 * Cancellation is deliberately not converted into a successful ToolResult: the
 * caller receives the CancellationException and can persist a recovery checkpoint.
 */
class ExecutionSupervisor(
    private val executor: suspend (ScopeSnapshot, ValidatedToolCall) -> ToolResult<Any>,
    private val ledger: ExecutionLedger = InMemoryExecutionLedger(),
    private val maxParallelism: Int = AgentHarness.MAX_PARALLEL_TOOL_CALLS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        provider: ToolProvider,
        ledger: ExecutionLedger = InMemoryExecutionLedger(),
        maxParallelism: Int = AgentHarness.MAX_PARALLEL_TOOL_CALLS,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this({ _, call -> provider.execute(call) }, ledger, maxParallelism, dispatcher)

    constructor(
        catalog: ToolCatalog,
        ledger: ExecutionLedger = InMemoryExecutionLedger(),
        maxParallelism: Int = AgentHarness.MAX_PARALLEL_TOOL_CALLS,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this({ scope, call -> catalog.execute(scope, call) }, ledger, maxParallelism, dispatcher)

    init { require(maxParallelism > 0) }

    private val keyedLocks = Mutex()
    private val resourceMutexes = mutableMapOf<String, Mutex>()
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ToolResult<Any>>>()

    suspend fun execute(
        scope: ScopeSnapshot,
        calls: List<ValidatedToolCall>,
        runId: String = scope.session.id,
        turn: Int = 0,
    ): ExecutionBatchResult {
        val items = calls.map { call ->
            ExecutableToolCall(call, ToolIdempotencyKey.forCall(runId, turn, call.call))
        }
        return execute(scope, items)
    }

    suspend fun execute(scope: ScopeSnapshot, calls: List<ExecutableToolCall>): ExecutionBatchResult = coroutineScope {
        if (calls.isEmpty()) return@coroutineScope ExecutionBatchResult(emptyList(), cancelled = false)

        val semaphore = Semaphore(maxParallelism)
        val jobs = calls.mapIndexed { index, item ->
            async {
                if (index >= maxParallelism) {
                    ToolResult(false, error = ToolError.DEVICE_BUSY, recoverable = true)
                } else {
                    semaphore.withPermit {
                        executeOnce(scope, item)
                    }
                }
            }
        }

        // Keep the call order stable for checkpointing and model follow-up.
        val results = withContext(dispatcher) { jobs.awaitAll() }
        ExecutionBatchResult(results, cancelled = false, idempotencyKeys = calls.map { it.idempotencyKey })
    }

    private suspend fun executeOnce(scope: ScopeSnapshot, item: ExecutableToolCall): ToolResult<Any> {
        ledger.lookup(item.idempotencyKey)?.let { return it }

        val (deferred, owner) = inFlightMutex.withLock {
            ledger.lookup(item.idempotencyKey)?.let { return@withLock CompletableDeferred<ToolResult<Any>>().apply { complete(it) } to false }
            inFlight[item.idempotencyKey]?.let { return@withLock it to false }
            CompletableDeferred<ToolResult<Any>>().also { inFlight[item.idempotencyKey] = it } to true
        }
        if (!owner) return deferred.await()

        return try {
            val definition = item.call.definition
            val result = try {
                withTimeout(definition.timeoutMillis) {
                    val key = definition.concurrencyKey
                    if (key.isBlank()) {
                        executor(scope, item.call)
                    } else {
                        resourceMutex(key).withLock { executor(scope, item.call) }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                ToolResult(false, error = ToolError.TIMEOUT, recoverable = true)
            } catch (_: CancellationException) {
                throw CancellationException("Tool execution cancelled")
            } catch (_: Throwable) {
                ToolResult(false, error = ToolError.FAILED, recoverable = true)
            }
            ledger.record(item.idempotencyKey, result)
            deferred.complete(result)
            result
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            inFlightMutex.withLock {
                if (inFlight[item.idempotencyKey] === deferred) inFlight.remove(item.idempotencyKey)
            }
        }
    }

    private suspend fun resourceMutex(key: String): Mutex = keyedLocks.withLock {
        resourceMutexes.getOrPut(key) { Mutex() }
    }
}
