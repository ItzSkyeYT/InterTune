/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.ui.screens.settings.ExtraSettingsPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The tester lock: a build for testers opens only with a tester's own code.
 *
 * Only in the Tester build type, which has it always (it is the app testers are given, InterTune Tester), and in the
 * Preview build type, which has it on only when the build was made with -Pintertune.testerLock=true: a Preview built
 * for our own phones, debug builds and every release have no lock. This folder is the Preview's and the tester
 * type takes it as it is (build.gradle.kts, sourceSets). The other build types have a stand-in of their own that
 * never holds. The rules are in [TesterLockRules], the door in [TesterDoor].
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
        // Back from #testers with a code moved to this device: look again, so the tester is not left at a gate that has changed.
        DisposableEffect(activity, session) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) session.resumed() }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
        val gate = session.gate.collectAsState().value ?: return false
        TesterGate(activity, gate, onOpen = session::open, onEdited = session::edited, onAnotherCode = session::useAnotherCode)
        return true
    }

    /**
     * The Testers page of Settings (TestersPage.kt), where the lock is on: its entry at the top of the list, its screen, and
     * what a search finds on it. A Preview made without the lock has none, as a debug build and a release have none.
     */
    internal val testers: ExtraSettingsPage? = if (BuildConfig.TESTER_LOCK) TestersPage else null

    /**
     * The tester's number for a report, from the code this device keeps, and never the code: a report can end up on GitHub, and a
     * code opens builds. Null where the lock is off and when no code is kept.
     */
    fun testerNumber(context: Context): Int? {
        if (!BuildConfig.TESTER_LOCK) return null
        return TesterLockStore(context.applicationContext).load().code?.let { TesterCode.numberOf(it) }
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

    private val remembered = MutableStateFlow(store.load())
    private val working = MutableStateFlow(false)

    /** What the lock remembers after its latest check, for the Testers page. */
    val memory: StateFlow<TesterMemory> = remembered

    /** Whether a check is under way, a launch's or a tester's own, for the Testers page. */
    val busy: StateFlow<Boolean> = working

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

    /**
     * The code in the field was changed: the answer to the one before is no longer about it. Except the
     * answer about the build, out of date: another code does not change that.
     */
    fun edited() {
        val gate = shown.value ?: return
        if (gate.busy || gate.message == null || gate.message == GateMessage.OUTDATED) return
        shown.value = gate.copy(message = null)
    }

    /**
     * The tester is back at the gate for a code another device holds, from #testers where they moved it: look again,
     * behind the gate and without changing what it shows, and the app opens if the code is theirs now. Nothing else
     * is looked at again on a return: a launch does that, and a build that is out of date is replaced, not retried.
     */
    fun resumed() {
        val gate = shown.value ?: return
        if (gate.busy || gate.message != GateMessage.IN_USE || checking?.isActive == true) return
        val code = store.load().code ?: return
        check(code)
    }

    /**
     * "Use another code", at the gate for a code another device holds: the field is back, with nothing under
     * it. The gate empties the field itself, which is the gate's; the code kept stays kept.
     */
    fun useAnotherCode() {
        val gate = shown.value ?: return
        if (gate.busy) return
        shown.value = gate.copy(message = null)
    }

    /**
     * "Check now" on the Testers page: the check a launch makes, for the code kept and with the app open as it is. A yes is
     * kept and shown there; a no puts the gate in front, as it does at any other time.
     */
    fun checkNow() {
        if (shown.value != null || checking?.isActive == true) return
        val code = store.load().code ?: return
        check(code)
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
        working.value = true
        checking = scope.launch {
            // The install number is made and written down before the door is first asked: the door holds
            // the code for it, so the phone must never lose a number the door has been given.
            val loaded = store.load()
            val kept = TesterLockRules.withInstall(loaded) { TesterInstall.make() }
            if (kept != loaded) store.save(kept)
            val answer = door.ask(code, build, BuildConfig.VERSION_CODE, kept.install.orEmpty())
            val after = TesterLockRules.afterCheck(build, kept, code, answer, System.currentTimeMillis())
            store.save(after.memory)
            remembered.value = after.memory
            // A good code the door refused this build with is shown in the field: it is the build or the device, not the code.
            val keepsCode = after.message == GateMessage.OUTDATED || after.message == GateMessage.IN_USE
            shown.value = if (after.opens) null
            else (shown.value ?: GateState(prefill = if (keepsCode) code else "")).copy(message = after.message, busy = false)
        }.also { it.invokeOnCompletion { working.value = false } }
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

    /** A file saved before a field existed loads without it: no install number, no time of the last yes. */
    fun load() = TesterMemory(
        code = prefs.getString(CODE, null),
        install = prefs.getString(INSTALL, null),
        openedBuild = prefs.getString(OPENED_BUILD, null),
        openedAt = prefs.getLong(OPENED_AT, 0L),
        claimed = prefs.getBoolean(CLAIMED, false),
        graceBuild = prefs.getString(GRACE_BUILD, null),
        graceSince = prefs.getLong(GRACE_SINCE, 0L),
        refusedBuild = prefs.getString(REFUSED_BUILD, null),
    )

    fun save(memory: TesterMemory) = prefs.edit(commit = true) {
        putString(CODE, memory.code)
        putString(INSTALL, memory.install)
        putString(OPENED_BUILD, memory.openedBuild)
        putLong(OPENED_AT, memory.openedAt)
        putBoolean(CLAIMED, memory.claimed)
        putString(GRACE_BUILD, memory.graceBuild)
        putLong(GRACE_SINCE, memory.graceSince)
        putString(REFUSED_BUILD, memory.refusedBuild)
    }

    private companion object {
        const val CODE = "code"
        const val INSTALL = "install"
        const val OPENED_BUILD = "opened_build"
        const val OPENED_AT = "opened_at"
        const val CLAIMED = "claimed"
        const val GRACE_BUILD = "grace_build"
        const val GRACE_SINCE = "grace_since"
        const val REFUSED_BUILD = "refused_build"
    }
}
