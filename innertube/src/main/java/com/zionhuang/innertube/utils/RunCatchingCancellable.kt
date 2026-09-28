/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube.utils

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but never turns a coroutine cancellation into a failed [Result].
 *
 * Plain `runCatching` catches [Throwable], [CancellationException] included, so a request that
 * gets cancelled (the caller stopped waiting on it) came back as an ordinary failed [Result]
 * instead of unwinding the coroutine, and callers such as YTPlayerUtils' fallback loop read that
 * as a normal per-client failure and kept going. Rethrowing here restores cooperative
 * cancellation. YTPlayerUtils' resolveOnce in the app uses it too.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
