package com.dd3boh.outertune.utils

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers that something failed for a key, so it is not retried immediately.
 *
 * Deliberately small and in memory: a negative cache is a note about the last few minutes, not a
 * fact worth storing, and forgetting everything on restart is the right behaviour when the reason
 * for the failure is usually a network that has since changed.
 */
class FailureMemo(private val cooldownMs: Long) {
    private val failures = ConcurrentHashMap<String, Long>()

    /** Whether [key] failed recently enough that it should not be retried yet. */
    fun none(key: String): Boolean {
        val until = failures[key] ?: return true
        if (SystemClock.elapsedRealtime() >= until) {
            failures.remove(key)
            return true
        }
        return false
    }

    fun note(key: String) {
        failures[key] = SystemClock.elapsedRealtime() + cooldownMs
    }

    fun clear() = failures.clear()
}
