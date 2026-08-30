package com.fsaint.androidagent.capabilities.screen

import java.util.concurrent.atomic.AtomicReference

internal class SingleUseGrantStore<T>(
    private val ttlMillis: Long,
    private val nowMillis: () -> Long,
) {
    private data class Entry<T>(val value: T, val expiresAtMillis: Long)

    private val entry = AtomicReference<Entry<T>?>(null)

    fun put(value: T) {
        entry.set(Entry(value, nowMillis() + ttlMillis))
    }

    fun hasGrant(): Boolean {
        val current = entry.get() ?: return false
        if (nowMillis() <= current.expiresAtMillis) return true
        entry.compareAndSet(current, null)
        return false
    }

    fun take(): T? {
        while (true) {
            val current = entry.get() ?: return null
            if (nowMillis() > current.expiresAtMillis) {
                entry.compareAndSet(current, null)
                return null
            }
            if (entry.compareAndSet(current, null)) return current.value
        }
    }

    fun clear() {
        entry.set(null)
    }
}
