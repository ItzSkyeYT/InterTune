/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import com.dd3boh.outertune.BuildConfig

/**
 * Finished, on the branch, and not announced yet.
 *
 * 0.11 is the recommendations release. 0.10.8 is a small one that happens to be cut from the same
 * branch, because the small things were built on top of the large ones and unpicking them would
 * mean two branches and two sets of fixes. So the engine's own row, the widget and everything
 * that only makes sense beside them are held behind this one switch instead, and the branch stays
 * single.
 *
 * What still ships in 0.10.8 is the part that has to: the listening log. It starts recording now
 * so that the engine has something to learn from on the day it arrives, and Pause listening
 * history under Settings > Library and content turns it off. The ledger of what it recorded stays
 * visible for the same reason, because a log nobody can see is not one anybody should accept.
 *
 * Debug builds have it on, release builds do not. The maintainer listens on the debug build
 * precisely because it has the engine, and a gate that took that away from him every time a test
 * build was installed was a gate that had outgrown its job. The release is what 0.10.8 is about,
 * and `assembleCoreRelease` is what proves the gate still holds.
 *
 * For 0.11: set [ENGINE] to true outright, flip `android:enabled` back to true on the two widget
 * components in AndroidManifest.xml, and delete this file along with the three `if (Unreleased.`
 * checks that reference it.
 */
object Unreleased {
    /**
     * The engine as a Quick picks source: Best recommendations, Try both, the context chips that
     * only appear above its row, and the settings that only steer it.
     */
    val ENGINE = BuildConfig.DEBUG
}
