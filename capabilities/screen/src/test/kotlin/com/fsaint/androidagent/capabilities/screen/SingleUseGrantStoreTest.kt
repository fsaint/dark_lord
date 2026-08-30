package com.fsaint.androidagent.capabilities.screen

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleUseGrantStoreTest {
    @Test
    fun grantCanBeConsumedOnlyOnce() {
        val store = SingleUseGrantStore<String>(ttlMillis = 60_000, nowMillis = { 100L })
        store.put("approved")

        assertEquals("approved", store.take())
        assertNull(store.take())
        assertFalse(store.hasGrant())
    }

    @Test
    fun concurrentConsumersCannotReuseGrant() {
        val store = SingleUseGrantStore<String>(ttlMillis = 60_000, nowMillis = { 100L })
        store.put("approved")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(listOf(Callable { store.take() }, Callable { store.take() }))
                .map { it.get() }

            assertEquals(1, results.count { it == "approved" })
            assertEquals(1, results.count { it == null })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun expiredGrantCannotBeConsumed() {
        var now = 100L
        val store = SingleUseGrantStore<String>(ttlMillis = 1_000, nowMillis = { now })
        store.put("approved")
        now = 1_101L

        assertFalse(store.hasGrant())
        assertNull(store.take())
    }
}
