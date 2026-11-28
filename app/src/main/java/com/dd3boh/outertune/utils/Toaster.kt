/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Every toast of the app, one at a time: a new one takes the place of the one showing.
 *
 * Android queues an app's toasts and shows each for its whole length, one after the other, so a
 * quick run of them (a radio that failed, the retry, a skip) went on talking for seconds after
 * what it was about. The newest is the one that matters (his word, 4 Nov 2026).
 *
 * Made with the application's context, so the toast kept here until the next one holds no screen.
 * Made on the main thread, whatever thread asks for it: Android closes the app when a toast is made
 * on a thread with no looper (#74), and a guest's Play next in a folder's menu asked from the
 * database's IO thread (28 Nov 2026). Asked from another thread, it is posted to the main looper
 * ([onMain], ToastOnMainThreadTest).
 */
object Toaster {
    private var showing: Toast? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun show(context: Context, text: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
        val app = context.applicationContext ?: context
        onMain(Looper.myLooper() == Looper.getMainLooper(), { main.post(it) }) {
            val toast = Toast.makeText(app, text, duration)
            synchronized(this) {
                showing?.cancel()
                showing = toast
            }
            toast.show()
        }
    }

    fun show(context: Context, @StringRes text: Int, duration: Int = Toast.LENGTH_SHORT) =
        show(context, context.getString(text), duration)

    /** [block] at once on the main thread ([onMainThread]); from any other, handed to [post] for the main looper. */
    internal fun onMain(onMainThread: Boolean, post: (Runnable) -> Unit, block: () -> Unit) {
        if (onMainThread) block() else post(Runnable { block() })
    }
}
