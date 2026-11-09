/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.os.Bundle
import android.os.Parcel
import android.util.Log

/**
 * What the app hands Android to keep when it is left, by key and size, in the log of debug builds
 * when it is large (64 KB or more): `adb logcat -s SavedStateSize`. #107: 527 KB of it closed the
 * app on a Galaxy S25 Ultra (TransactionTooLargeException as the app went to the background), 472 KB
 * of that the navigation's history (KeptHistory). Every key of 1 KB or more is listed, with what is
 * inside it.
 */
internal object SavedStateSize {
    private const val TAG = "SavedStateSize"
    private const val LISTED_FROM = 1024
    private const val LOGGED_FROM = 64 * 1024
    private const val DEEPEST = 6

    fun log(state: Bundle) {
        val all = sizeOf(state)
        if (all < LOGGED_FROM) return
        Log.i(TAG, "all: $all bytes")
        walk(state, 0)
    }

    private fun walk(bundle: Bundle, depth: Int) {
        for (key in bundle.keySet().sorted()) {
            @Suppress("DEPRECATION")
            val value = runCatching { bundle.get(key) }.getOrNull()
            val size = sizeOf(value)
            if (size < LISTED_FROM) continue
            val count = when (value) {
                is Array<*> -> " (${value.size} in it)"
                is List<*> -> " (${value.size} in it)"
                else -> ""
            }
            Log.i(TAG, "${"  ".repeat(depth + 1)}$key: $size bytes$count")
            if (depth >= DEEPEST) continue
            when (value) {
                is Bundle -> walk(value, depth + 1)
                is List<*> -> value.forEachIndexed { i, item -> if (item is Bundle) inside("$key[$i]", item, depth + 1) }
                is Array<*> -> value.forEachIndexed { i, item -> if (item is Bundle) inside("$key[$i]", item, depth + 1) }
            }
        }
    }

    private fun inside(name: String, bundle: Bundle, depth: Int) {
        val size = sizeOf(bundle)
        if (size < LISTED_FROM) return
        Log.i(TAG, "${"  ".repeat(depth + 1)}$name: $size bytes")
        walk(bundle, depth + 1)
    }

    /** The bytes [value] takes in a parcel, as a binder call carries it; -1 if it cannot be written. */
    fun sizeOf(value: Any?): Int {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeValue(value)
            parcel.dataSize()
        } catch (e: RuntimeException) {
            -1
        } finally {
            parcel.recycle()
        }
    }
}
