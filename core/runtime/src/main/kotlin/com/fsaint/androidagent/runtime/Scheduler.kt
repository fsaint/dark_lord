package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.ToolError

data class Schedule(val id: String, val dueAtEpochMs: Long, val payload: String, val maxRetries: Int = 3)
data class SchedulerResult(val success: Boolean, val error: ToolError? = null)
class ScheduleStoreException(val code: ToolError) : RuntimeException(code.name)

interface ScheduleStore {
    suspend fun put(schedule: Schedule)
    suspend fun remove(id: String): Boolean
    suspend fun get(id: String): Schedule?
    suspend fun pending(): List<Schedule>
}

class InMemoryScheduleStore : ScheduleStore {
    val items = mutableListOf<Schedule>()
    override suspend fun put(schedule: Schedule) { items.removeAll { it.id == schedule.id }; items += schedule }
    override suspend fun remove(id: String): Boolean = items.removeIf { it.id == id }
    override suspend fun get(id: String): Schedule? = items.firstOrNull { it.id == id }
    override suspend fun pending(): List<Schedule> = items.toList()
}

class DurableScheduler(
    private val store: ScheduleStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxPayloadBytes: Int = 64 * 1024,
) {
    suspend fun schedule(schedule: Schedule): SchedulerResult {
        if (schedule.id.isBlank() || schedule.dueAtEpochMs <= now() || schedule.payload.toByteArray().size > maxPayloadBytes || schedule.maxRetries !in 0..10) {
            return SchedulerResult(false, ToolError.OS_RESTRICTED)
        }
        return try { store.put(schedule); SchedulerResult(true) } catch (e: ScheduleStoreException) { SchedulerResult(false, e.code) } catch (_: Throwable) { SchedulerResult(false, ToolError.TIMEOUT) }
    }

    suspend fun cancel(id: String): SchedulerResult = try {
        if (store.remove(id)) SchedulerResult(true) else SchedulerResult(false, ToolError.NOT_FOUND)
    } catch (e: ScheduleStoreException) { SchedulerResult(false, e.code) } catch (_: Throwable) { SchedulerResult(false, ToolError.TIMEOUT) }

    suspend fun pending(): List<Schedule> = store.pending()
}
