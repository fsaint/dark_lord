package com.fsaint.androidagent.runtime

import com.fsaint.androidagent.model.ToolError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerTest {
    @Test
    fun validatesDueTimeAndPayloadBounds() = runTest {
        val scheduler = DurableScheduler(InMemoryScheduleStore(), now = { 1_000 }, maxPayloadBytes = 32)
        assertEquals(ToolError.OS_RESTRICTED, scheduler.schedule(Schedule("a", 999, "x")).error)
        assertEquals(ToolError.OS_RESTRICTED, scheduler.schedule(Schedule("b", 2_000, "x".repeat(100))).error)
    }

    @Test
    fun replacingSameIdIsIdempotentAndCancelReturnsNotFound() = runTest {
        val store = InMemoryScheduleStore()
        val scheduler = DurableScheduler(store, now = { 1_000 })
        val first = Schedule("a", 2_000, "one")
        val second = first.copy(payload = "two")
        assertTrue(scheduler.schedule(first).success)
        assertTrue(scheduler.schedule(second).success)
        assertEquals(listOf(second), store.items)
        assertTrue(scheduler.cancel("a").success)
        assertEquals(ToolError.NOT_FOUND, scheduler.cancel("a").error)
    }

    @Test
    fun preservesPendingSchedulesWhenBackendTimesOut() = runTest {
        val scheduler = DurableScheduler(object : ScheduleStore {
            override suspend fun put(schedule: Schedule) = throw ScheduleStoreException(ToolError.TIMEOUT)
            override suspend fun remove(id: String) = throw ScheduleStoreException(ToolError.TIMEOUT)
            override suspend fun get(id: String): Schedule? = null
            override suspend fun pending(): List<Schedule> = emptyList()
        }, now = { 1_000 })
        assertEquals(ToolError.TIMEOUT, scheduler.schedule(Schedule("a", 2_000, "x")).error)
    }
}
