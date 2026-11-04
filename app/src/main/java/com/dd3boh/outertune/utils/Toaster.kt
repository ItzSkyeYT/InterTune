/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
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
 * Called on the main thread, as Toast.makeText always was (ToastOnMainThreadTest).
 */
object Toaster {
    private var showing: Toast? = null

    fun show(context: Context, text: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(context.applicationContext ?: context, text, duration)
        synchronized(this) {
            showing?.cancel()
            showing = toast
        }
        toast.show()
    }

    fun show(context: Context, @StringRes text: Int, duration: Int = Toast.LENGTH_SHORT) =
        show(context, context.getString(text), duration)
}
