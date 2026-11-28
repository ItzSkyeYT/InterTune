/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import com.dd3boh.outertune.utils.Toaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android closes the app when a toast is made on a thread with no looper (#74, 3 Nov 2026: the
 * Developer settings' artist linking showed its "complete" toast from the scanner's worker
 * thread). This reads the source, as the flag tests do: a toast whose nearest enclosing coroutine
 * runs on IO, Default or the scanner's dispatcher, with no switch back to Main in between, fails.
 *
 * So does a toast in a suspend function's own body (4 Nov 2026): a suspend function runs on its
 * caller's thread, and the M3U import's "Failed to import" was made in loadM3u, which the dialog
 * starts on the scanner's dispatcher. The toast threw there, and the person was told nothing.
 *
 * Toaster.show is safe from any thread (28 Nov 2026): it makes its toast on the main thread and
 * posts it there when asked from another, as a guest's Play next in a folder's menu asked from the
 * database's IO thread, out of this search's sight. So the search is for Toast.makeText itself.
 */
class ToastOnMainThreadTest {
    private val sources = File("src/main/java")
    private val offMain = Regex(
        """\b(launch|withContext|async)\((Dispatchers\.IO|Dispatchers\.Default|lmScannerCoroutine)""" +
            """|\bCoroutineScope\((Dispatchers\.IO|Dispatchers\.Default|lmScannerCoroutine)\)\.(launch|async)"""
    )
    private val onMain = Regex(
        """\bwithContext\((Dispatchers\.)?Main|\blaunch\(Dispatchers\.Main|\brunBlocking\(Dispatchers\.Main""" +
            """|\bHandler\(Looper\.getMainLooper\(\)\)\.post|\brunOnUiThread\b|\bCoroutineScope\((Dispatchers\.)?Main"""
    )
    private val suspendFunction = Regex("""\bsuspend\b(\s+\w+)*\s+fun\b""")

    /** Each toast made off the main thread, as file:line. */
    private fun offMainToasts(): List<String> = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
        offMainToasts(file.readLines()).map { "${file.relativeTo(sources).path}:${it + 1}" }
    }.toList()

    /** The indices of [lines] that make a toast off the main thread. */
    private fun offMainToasts(lines: List<String>): List<Int> = lines.indices.filter { "Toast.makeText" in lines[it] }.filter { at ->
        // A block opened on the toast's own line, before it: withContext(Dispatchers.Main) { Toast.makeText(...) }.
        val before = lines[at].substring(0, lines[at].indexOf("Toast.makeText"))
        if (onMain.containsMatchIn(before)) return@filter false
        if (offMain.containsMatchIn(before)) return@filter true
        var depth = 0
        for (j in at - 1 downTo maxOf(0, at - 400)) {
            val line = lines[j]
            depth += line.count { it == '}' } - line.count { it == '{' }
            if (depth < 0) {
                // The block this toast sits in opens here: which thread is it?
                if (onMain.containsMatchIn(line)) return@filter false
                if (offMain.containsMatchIn(line)) return@filter true
                // The body of a function: a suspend function's thread is its caller's.
                declaration(lines, j)?.let { return@filter suspendFunction.containsMatchIn(it) }
                depth = 0
            }
        }
        false
    }

    /** The function declaration whose body opens on line [at], or null when the block there is not one. */
    private fun declaration(lines: List<String>, at: Int): String? {
        for (i in at downTo maxOf(0, at - 12)) {
            if (Regex("""\bfun\b""").containsMatchIn(lines[i])) return lines.subList(i, at + 1).joinToString(" ")
            // Past the start of the signature: the block is a lambda or a class, not a function.
            if (i != at && ('{' in lines[i] || '}' in lines[i])) return null
        }
        return null
    }

    @Test
    fun `no toast is made off the main thread`() {
        assertEquals(emptyList<String>(), offMainToasts())
    }

    @Test
    fun `the search finds a toast in a suspend function and in a scope made off the main thread`() {
        val was = """
            suspend fun loadM3u(
                context: Context,
                uri: Uri,
            ): List<String> {
                runCatching {
                    read(uri)
                }.onFailure {
                    reportException(it)
                    Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show()
                }
                return emptyList()
            }
            suspend fun loadOnMain(context: Context) {
                runCatching { read() }.onFailure {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            fun onClick(context: Context) {
                Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show()
            }
            fun later(context: Context) = CoroutineScope(lmScannerCoroutine).launch {
                Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show()
            }
            fun posted(context: Context) = CoroutineScope(lmScannerCoroutine).launch {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show()
                }
            }
            suspend fun sameLine(context: Context) {
                withContext(Dispatchers.Main) { Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show() }
            }
            suspend fun scoped(context: Context) {
                CoroutineScope(Dispatchers.Main.immediate).launch { Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show() }
            }
            suspend fun sameLineOff(context: Context) {
                withContext(Dispatchers.IO) { Toast.makeText(context, R.string.m3u_import_playlist_failed, Toast.LENGTH_SHORT).show() }
            }
            suspend fun anyThread(context: Context) {
                withContext(Dispatchers.IO) { Toaster.show(context, R.string.m3u_import_playlist_failed) }
            }
        """.trimIndent().lines()
        // The suspend function's own toast, the one in the scanner's scope and the one switched to IO
        // on its own line; not the one switched to Main, the plain function's (a click, on Main), the
        // one posted to the main looper, those sent to Main on their own line, nor Toaster's from IO,
        // which it posts to the main looper itself.
        assertEquals(listOf(9, 24, 38), offMainToasts(was).map { it + 1 })
    }

    @Test
    fun `Toaster makes its toast on the main thread, posted there when asked from another`() {
        val posted = mutableListOf<Runnable>()
        var made = 0
        Toaster.onMain(onMainThread = true, post = { posted += it }) { made++ }
        assertEquals("on the main thread: at once", 1, made)
        assertTrue(posted.isEmpty())
        Toaster.onMain(onMainThread = false, post = { posted += it }) { made++ }
        assertEquals("from another thread: not there", 1, made)
        posted.single().run()
        assertEquals("but on the main looper's turn", 2, made)
        // Its one toast is made inside that, which is told whether this is the main thread.
        val toaster = File(sources, "com/dd3boh/outertune/utils/Toaster.kt").readText()
        val show = toaster.substringAfter("fun show(context: Context, text: CharSequence?").substringBefore("\n    fun show(")
        val hop = show.indexOf("onMain(Looper.myLooper() == Looper.getMainLooper(), { main.post(it) }) {")
        assertTrue(show, hop >= 0 && hop < show.indexOf("Toast.makeText("))
        assertEquals(1, Regex("""Toast\.makeText\(""").findAll(toaster).count())
        assertTrue("private val main by lazy { Handler(Looper.getMainLooper()) }" in toaster)
    }
}
