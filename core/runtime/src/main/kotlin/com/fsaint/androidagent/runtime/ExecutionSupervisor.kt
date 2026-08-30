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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.security.MessageDigest

/** Result of atomically reserving an idempotency key. */
sealed interface ExecutionReservation {
    data object Reserved : ExecutionReservation
    data class Completed(val result: ToolResult<Any>) : ExecutionReservation
}

/** Durable boundary used to make completed tool calls safe to resume. */
interface ExecutionLedger {
    /** Reserves a new key, or waits for and returns the existing completion. */
    suspend fun reserveOrReturnCompleted(idempotencyKey: String): ExecutionReservation
    suspend fun complete(idempotencyKey: String, result: ToolResult<Any>)
    /** Releases an unfinished reservation after cancellation or an owner failure. */
    suspend fun release(idempotencyKey: String)
}

/** Simple ledger for runtime tests and process-local callers. */
class InMemoryExecutionLedger : ExecutionLedger {
    private class Entry {
        var result: ToolResult<Any>? = null
        val completed = CompletableDeferred<ToolResult<Any>>()
    }

    private sealed interface Lookup {
        data object New : Lookup
        data class Existing(val completed: CompletableDeferred<ToolResult<Any>>) : Lookup
        data class Done(val result: ToolResult<Any>) : Lookup
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    override suspend fun reserveOrReturnCompleted(idempotencyKey: String): ExecutionReservation {
        require(idempotencyKey.isNotBlank())
        return when (val lookup = mutex.withLock {
            val entry = entries[idempotencyKey]
            when {
                entry == null -> {
                    entries[idempotencyKey] = Entry()
                    Lookup.New
                }
                entry.result != null -> Lookup.Done(entry.result!!)
                else -> Lookup.Existing(entry.completed)
            }
        }) {
            Lookup.New -> ExecutionReservation.Reserved
            is Lookup.Done -> ExecutionReservation.Completed(lookup.result)
            is Lookup.Existing -> ExecutionReservation.Completed(lookup.completed.await())
        }
    }

    override suspend fun complete(idempotencyKey: String, result: ToolResult<Any>) {
        mutex.withLock {
            val entry = entries.getOrPut(idempotencyKey) { Entry() }
            if (entry.result == null) {
                entry.result = result
                entry.completed.complete(result)
            }
        }
    }

    override suspend fun release(idempotencyKey: String) {
        mutex.withLock {
            val entry = entries[idempotencyKey]
            if (entry != null && entry.result == null) {
                entries.remove(idempotencyKey)
                entry.completed.completeExceptionally(CancellationException("Execution reservation released"))
            }
        }
    }
}

/** Stable, non-secret idempotency key for a run/turn/tool/arguments tuple. */
object ToolIdempotencyKey {
    fun forCall(runId: String, turn: Int, call: ToolCall): String {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(turn >= 0) { "turn must not be negative" }
        val arguments = call.arguments.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${lengthPrefix(key)}${lengthPrefix(value)}"
        }
        val canonical = buildString {
            append(lengthPrefix(runId))
            append(lengthPrefix(turn.toString()))
            append(lengthPrefix(call.name))
            append(lengthPrefix(call.arguments.size.toString()))
            append(arguments)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "tool-v1:$digest"
    }

    private fun lengthPrefix(value: String): String =
        "${value.toByteArray(Charsets.UTF_8).size}:$value"
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
class ExecutionSupervisor private constructor(
    private val executor: suspend (ScopeSnapshot, ValidatedToolCall) -> ToolResult<Any>,
    private val ledger: ExecutionLedger = InMemoryExecutionLedger(),
    private val maxParallelism: Int = AgentHarness.MAX_PARALLEL_TOOL_CALLS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        catalog: ToolCatalog,
        ledger: ExecutionLedger = InMemoryExecutionLedger(),
        maxParallelism: Int = AgentHarness.MAX_PARALLEL_TOOL_CALLS,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this({ scope, call -> catalog.execute(scope, call) }, ledger, maxParallelism, dispatcher)

    init { require(maxParallelism > 0) }

    private val keyedLocks = Mutex()
    private val resourceMutexes = mutableMapOf<String, Mutex>()

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
            async(dispatcher) {
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
        val results = jobs.awaitAll()
        ExecutionBatchResult(results, cancelled = false, idempotencyKeys = calls.map { it.idempotencyKey })
    }

    private suspend fun executeOnce(scope: ScopeSnapshot, item: ExecutableToolCall): ToolResult<Any> {
        when (val reservation = ledger.reserveOrReturnCompleted(item.idempotencyKey)) {
            is ExecutionReservation.Completed -> return reservation.result
            ExecutionReservation.Reserved -> Unit
        }
        var completed = false
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
            ledger.complete(item.idempotencyKey, result)
            completed = true
            result
        } finally {
            if (!completed) ledger.release(item.idempotencyKey)
        }
    }

    private suspend fun resourceMutex(key: String): Mutex = keyedLocks.withLock {
        resourceMutexes.getOrPut(key) { Mutex() }
    }
}
