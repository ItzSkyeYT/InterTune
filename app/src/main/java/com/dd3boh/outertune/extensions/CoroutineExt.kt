package com.dd3boh.outertune.extensions

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

fun <T> Flow<T>.collect(scope: CoroutineScope, action: suspend (value: T) -> Unit) {
    scope.launch {
        collect(action)
    }
}

fun <T> Flow<T>.collectLatest(scope: CoroutineScope, action: suspend (value: T) -> Unit) {
    scope.launch {
        collectLatest(action)
    }
}

val SilentHandler = CoroutineExceptionHandler { _, _ -> }

/**
 * Waits on the calling thread for this job to end, for [timeoutMs] at most. False when it had not
 * ended by then: the job is left running and nothing is cancelled. For the few places that have to
 * hold a thread until a job has landed (a service's onDestroy) and must not hold it for good.
 */
fun Job.joinWithin(timeoutMs: Long): Boolean = runBlocking { withTimeoutOrNull(timeoutMs) { join() } != null }
