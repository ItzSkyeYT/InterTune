/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.net.SocketTimeoutException

/**
 * A step of finding a song that we gave up on ourselves, after [ms] with no answer. A socket
 * timeout as far as anything else can tell, so MusicService shows it as "Timeout" as it always
 * has; and never a word from YouTube, so it counts against no client and never trips the throttle.
 */
class StepTimedOut(val step: String, val ms: Long) : SocketTimeoutException("$step: no answer in ${StepLog.limit(ms)}")
