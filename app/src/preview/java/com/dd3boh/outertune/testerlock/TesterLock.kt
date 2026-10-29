/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.core.content.edit
import com.dd3boh.outertune.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The tester lock: a preview published for testers opens only with a tester's own code.
 *
 * Only in the preview build type (src/preview), and on only when the build was made with
 * -Pintertune.testerLock=true, which publish-preview.sh passes: previews built for our own phones,
 * debug builds and every release have no lock. The other build types have a stand-in of their own that never holds.
 * The rules are in [TesterLockRules], the door in [TesterDoor].
 */
object TesterLock {
    /**
     * Whether the gate holds the app, drawing it when it does. MainActivity draws nothing else
     * meanwhile, and starts none of its work.
     */
    @Composable
    fun holds(activity: ComponentActivity): Boolean {
        if (!BuildConfig.TESTER_LOCK) return false
        // Each time the activity is made is a launch: the rules decide at once, before the first frame.
        val session = remember(activity) { TesterLockSession.of(activity.applicationContext).also { it.launch() } }
        val gate = session.gate.collectAsState().value ?: return false
        TesterGate(activity, gate, onOpen = session::open)
        return true
    }
}

/** The gate as it is drawn: the code to start the field with, the message under it, and a check under way. */
internal data class GateState(
    val prefill: String = "",
    val message: GateMessage? = null,
    val busy: Boolean = false,
)

/** The lock for the whole process: what the gate shows, and the one check at a time. */
internal class TesterLockSession private constructor(
    private val store: TesterLockStore,
    private val door: TesterDoor,
) {
    private val build = BuildConfig.VERSION_NAME
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var checking: Job? = null

    private val shown = MutableStateFlow<GateState?>(null)

    /** The gate, or null while the app is open. */
    val gate: StateFlow<GateState?> = shown

    fun launch() {
        // A check under way decides for itself.
        if (checking?.isActive == true) return
        when (val launch = TesterLockRules.atLaunch(BuildConfig.TESTER_LOCK, build, store.load(), System.currentTimeMillis())) {
            AtLaunch.Open -> shown.value = null
            is AtLaunch.OpenAndCheck -> {
                shown.value = null
                check(launch.code)
            }
            AtLaunch.Ask -> shown.value = GateState()
            is AtLaunch.AskAndCheck -> {
                shown.value = GateState(prefill = launch.code, busy = true)
                check(launch.code)
            }
        }
    }

    /** The Open button, with what is in the field. */
    fun open(typed: String) {
        val gate = shown.value ?: return
        if (gate.busy || checking?.isActive == true) return
        val code = TesterCode.normalise(typed)
        // Not a code at all: said at once, and nothing is sent.
        if (!TesterCode.isWellFormed(code)) {
            shown.value = gate.copy(message = GateMessage.UNKNOWN)
            return
        }
        shown.value = gate.copy(message = null, busy = true)
        check(code)
    }

    private fun check(code: String) {
        checking = scope.launch {
            val answer = door.ask(code, build, BuildConfig.VERSION_CODE)
            val after = TesterLockRules.afterCheck(build, store.load(), code, answer, System.currentTimeMillis())
            store.save(after.memory)
            shown.value = if (after.opens) null else (shown.value ?: GateState()).copy(message = after.message, busy = false)
        }
    }

    companion object {
        @Volatile
        private var session: TesterLockSession? = null

        fun of(context: Context): TesterLockSession = session ?: synchronized(this) {
            session ?: TesterLockSession(TesterLockStore(context), TesterDoor(BuildConfig.TESTER_DOOR)).also { session = it }
        }
    }
}

/** [TesterMemory] in a preferences file of its own, apart from the app's settings. */
internal class TesterLockStore(context: Context) {
    private val prefs = context.getSharedPreferences("tester_lock", Context.MODE_PRIVATE)

    fun load() = TesterMemory(
        code = prefs.getString(CODE, null),
        openedBuild = prefs.getString(OPENED_BUILD, null),
        graceBuild = prefs.getString(GRACE_BUILD, null),
        graceSince = prefs.getLong(GRACE_SINCE, 0L),
    )

    fun save(memory: TesterMemory) = prefs.edit(commit = true) {
        putString(CODE, memory.code)
        putString(OPENED_BUILD, memory.openedBuild)
        putString(GRACE_BUILD, memory.graceBuild)
        putLong(GRACE_SINCE, memory.graceSince)
    }

    private companion object {
        const val CODE = "code"
        const val OPENED_BUILD = "opened_build"
        const val GRACE_BUILD = "grace_build"
        const val GRACE_SINCE = "grace_since"
    }
}
