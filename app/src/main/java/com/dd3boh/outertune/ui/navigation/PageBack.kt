/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.navigation

import android.annotation.SuppressLint
import android.util.Log
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import kotlinx.coroutines.launch

private const val TAG = "PageBack"

/**
 * Back on the pages, between Android and the nav host (GitHub #210).
 *
 * The nav host follows a back gesture with the pages: it takes the page on top and the one under it
 * and slides the one off over the other. It takes them from its own copy of the list of pages, which
 * catches up with the real list a moment after every change, and only when its work for the gesture
 * comes round, a moment after the gesture began. A page opened or closed in between, and the nav
 * host asked the navigation library to move a page that was no longer there, which ends the app:
 * "Cannot transition entry that is not in the back stack" (navigation-compose 2.9.8. 2.10 takes the
 * pages from the list itself, but brings Compose 1.11 with it and wants compileSdk 37 and AGP 9.2).
 *
 * So the nav host's own handler stands behind this one, which is put in the place it had among the
 * app's other handlers and is on exactly when it is, so every other Back comes first or after as it
 * always did. A gesture is handed on as it comes, and a look put in the queue just before the nav
 * host's work sees whether the pages changed since. If they did, the nav host's work is called off
 * before it runs, and the gesture is a plain Back: the page on top goes when the finger lets go.
 *
 * With Back animations off every page's Back is that plain one. It used to follow the finger anyway,
 * only with another transition, although the setting says swiping back follows your finger.
 *
 * [pages] is the list the nav host copies (its navigator's back stack), [later] puts a block in the
 * queue the nav host's work goes in, and [plainBack] takes the page on top away.
 */
internal class PageBack(
    private val pages: () -> List<Any>,
    private val later: (() -> Unit) -> Unit,
    private val plainBack: () -> Unit,
) {
    /** Back animations. Off, the nav host never sees a Back. */
    var animations = true

    /** Where the nav host's own handler stood among the app's. On whenever that one is. */
    val door: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) = started(backEvent)
        override fun handleOnBackProgressed(backEvent: BackEventCompat) = progressed(backEvent)
        override fun handleOnBackPressed() = pressed()
        override fun handleOnBackCancelled() = cancelled()
    }

    /** Where the nav host puts its own handler instead of the app's dispatcher. */
    val inner = OnBackPressedDispatcher(null) { on -> door.isEnabled = on }

    private class Gesture {
        /** The nav host was given it and follows it. */
        var handedOn = false

        /** Taken back from the nav host, or never given to it: a plain Back. */
        var plain = false

        /** The finger let go, or the Back came as a key or a button. */
        var letGo = false

        /** Abandoned: the finger went back to the edge. */
        var over = false
    }

    private var gesture: Gesture? = null

    // The dispatcher's own calls for handing a gesture on, which are public and marked as being for
    // tests: here they hand the gesture to the nav host's handler, as Android hands it to the app's.
    @SuppressLint("VisibleForTests")
    private fun started(event: BackEventCompat) {
        val g = Gesture().also { gesture = it }
        if (!animations) {
            g.plain = true
            return
        }
        handOn(g) { inner.dispatchOnBackStarted(event) }
    }

    @SuppressLint("VisibleForTests")
    private fun progressed(event: BackEventCompat) {
        val g = gesture ?: return
        if (g.handedOn && !g.plain) inner.dispatchOnBackProgressed(event)
    }

    private fun pressed() {
        // None without a gesture first: a key, the three buttons, Android 13 and older.
        val g = gesture ?: Gesture()
        gesture = null
        g.letGo = true
        when {
            g.plain -> plainBack()
            g.handedOn -> inner.onBackPressed()
            !animations -> plainBack()
            // The nav host starts its work for this one now, as for a gesture.
            else -> handOn(g) { inner.onBackPressed() }
        }
    }

    @SuppressLint("VisibleForTests")
    private fun cancelled() {
        val g = gesture ?: return
        gesture = null
        g.over = true
        if (g.handedOn && !g.plain) inner.dispatchOnBackCancelled()
    }

    /**
     * Hands a Back to the nav host, which puts its work for it in the queue. The look goes in first,
     * so it runs right before that work: anything that changes the pages before the work runs has
     * changed them by the time the look runs, and nothing can run between the two.
     */
    @SuppressLint("VisibleForTests")
    private fun handOn(g: Gesture, hand: () -> Unit) {
        val seen = pages()
        g.handedOn = true
        later {
            // The list is a new one after every change: the same list is the same pages.
            if (g.plain || g.over || pages() === seen) return@later
            g.plain = true
            // The work has not run, and now never will.
            inner.dispatchOnBackCancelled()
            Log.w(TAG, "The pages changed as Back began: a plain Back, the nav host left out (#210)")
            if (g.letGo) plainBack()
        }
        hand()
    }
}

/**
 * activity-compose's own local behind LocalOnBackPressedDispatcherOwner. Nothing in the app provides
 * it, so every BackHandler finds its owner from the window it is drawn in: the activity's, or a
 * dialog's (the poll's Back steps through its questions so, in its own window). Given null again
 * inside a page, it is not provided there either, and each page finds its owner as before.
 */
@Suppress("UNCHECKED_CAST")
private fun backOwnerLocal(any: OnBackPressedDispatcherOwner): ProvidableCompositionLocal<OnBackPressedDispatcherOwner?>? =
    (LocalOnBackPressedDispatcherOwner provides any).compositionLocal as? ProvidableCompositionLocal<OnBackPressedDispatcherOwner?>

/** The local to give null again inside each page (PageBackOwner), set by PagesBack. */
private val LocalPagesBackReset = staticCompositionLocalOf<ProvidableCompositionLocal<OnBackPressedDispatcherOwner?>?> { null }

/**
 * The nav host, with its own back handler behind [PageBack]. Only the nav host's handler: inside
 * each page (PageBackOwner in screen) Back is looked up as it always was, so a page's handlers, a
 * selection's, a search's, a dialog's, go in among the app's or the dialog's as before.
 */
@Composable
fun PagesBack(navController: NavHostController, backAnimations: Boolean, navHost: @Composable () -> Unit) {
    val app = checkNotNull(LocalOnBackPressedDispatcherOwner.current) { "PagesBack needs an OnBackPressedDispatcherOwner" }
    val reset = remember(app) { backOwnerLocal(app) }
    if (reset == null) {
        // Not a local that can be given null again (another activity-compose): the nav host as it was.
        navHost()
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val back = remember(navController) {
        val navigator = navController.navigatorProvider.getNavigator(ComposeNavigator::class.java)
        PageBack(
            pages = { navigator.backStack.value },
            // The queue the nav host's work goes in: both come from this composition's coroutines.
            later = { block -> scope.launch { block() } },
            // As the nav host's own handler: only with a page under the one on top.
            plainBack = { if (navigator.backStack.value.size > 1) navController.popBackStack() },
        )
    }
    SideEffect { back.animations = backAnimations }
    // Put in where the nav host put its own handler, with the same lifecycle: the same place among
    // the app's handlers, also after the app comes back to the front and they all go in again.
    DisposableEffect(app, lifecycleOwner, back) {
        app.onBackPressedDispatcher.addCallback(lifecycleOwner, back.door)
        onDispose { back.door.remove() }
    }
    val owner = remember(back, app) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher: OnBackPressedDispatcher get() = back.inner
            override val lifecycle: Lifecycle get() = app.lifecycle
        }
    }
    CompositionLocalProvider(
        LocalOnBackPressedDispatcherOwner provides owner,
        LocalPagesBackReset provides reset,
        content = navHost,
    )
}

/** Inside a page: Back is looked up as it was before PagesBack put the nav host's behind it. */
@Composable
fun PageBackOwner(content: @Composable () -> Unit) {
    val reset = LocalPagesBackReset.current
    if (reset == null) content()
    else CompositionLocalProvider(reset provides null, content = content)
}
