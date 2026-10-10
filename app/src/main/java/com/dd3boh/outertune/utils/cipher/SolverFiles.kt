/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import java.io.File

/**
 * What the solving of a web client's address keeps between runs of the app, in a folder of the
 * app's own cache: YouTube's player script as it was last fetched, when the page that names the
 * current player was last asked, and that script as the solver cut it down.
 *
 * Without it the first song of every process paid for all three again: the script is two and a
 * half megabytes to fetch, and parsing it is nearly all of the solver's work, 3.4 s on a Pixel 5
 * where a question asked with the cut-down script takes half a second (10 Oct 2026). A player
 * stays current for days.
 *
 * One player at a time: keeping a new one removes what was kept of any other. A player is known
 * by the short name YouTube gives it, and only a name of that shape is ever made into a file's
 * name. Everything here is a cache. A file that is missing, cannot be read or cannot be written
 * is the same as nothing kept, and the app fetches and solves as it did before.
 */
class SolverFiles(private val folder: File) {
    /** The player last seen to be the current one, and when, in wall clock milliseconds. */
    class Current(val id: String, val seenAt: Long)

    fun current(): Current? = attempt {
        val parts = File(folder, CURRENT).readText().trim().split(' ')
        val id = parts.getOrNull(0)?.takeIf { ID.matches(it) } ?: return@attempt null
        val seenAt = parts.getOrNull(1)?.toLongOrNull() ?: return@attempt null
        Current(id, seenAt)
    }

    /** The script of player [id], or null when it is not the one kept. */
    fun script(id: String): String? = read(id, SCRIPT)

    /** Keeps [text] as the script of player [id], seen to be current at [seenAt], and nothing of any other player. */
    fun keepScript(id: String, text: String, seenAt: Long) {
        if (!ID.matches(id)) return
        attempt {
            folder.mkdirs()
            folder.listFiles()?.filterNot { it.name.startsWith("$id.") }?.forEach { it.delete() }
            write(File(folder, id + SCRIPT), text)
            write(File(folder, CURRENT), "$id $seenAt")
        }
    }

    /** Notes that the page still named player [id] at [at]. Only for the player whose script is kept. */
    fun seen(id: String, at: Long) {
        if (!ID.matches(id) || !File(folder, id + SCRIPT).isFile) return
        attempt { write(File(folder, CURRENT), "$id $at") }
    }

    /** The script of player [id] as the solver cut it down, or null when there is none. */
    fun prepared(id: String): String? = read(id, PREPARED)

    fun keepPrepared(id: String, text: String) {
        // Only beside the script it was made from: a prepared script alone would outlive its player.
        if (!ID.matches(id) || !File(folder, id + SCRIPT).isFile) return
        attempt { write(File(folder, id + PREPARED), text) }
    }

    /** For a prepared script that did not give an answer: it is not trusted a second time, in this run or the next. */
    fun dropPrepared(id: String) {
        if (ID.matches(id)) attempt { File(folder, id + PREPARED).delete() }
    }

    private fun read(id: String, kind: String): String? {
        if (!ID.matches(id)) return null
        return attempt { File(folder, id + kind).takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readText() }
    }

    /** Written beside and moved into place, so that a process killed halfway leaves the old file or none. */
    private fun write(file: File, text: String) {
        val half = File(file.parentFile, file.name + ".part")
        half.writeText(text)
        if (!half.renameTo(file)) {
            file.delete()
            if (!half.renameTo(file)) half.delete()
        }
    }

    private fun <T> attempt(work: () -> T?): T? = runCatching(work).getOrNull()

    companion object {
        private const val CURRENT = "current"
        private const val SCRIPT = ".player.js"
        private const val PREPARED = ".prepared.js"

        /** As PlayerScripts reads a player's name out of the page. */
        private val ID = Regex("""[A-Za-z0-9_-]{8,16}""")

        /** A player script is about two and a half megabytes. Anything much larger is not one. */
        private const val MAX_BYTES = 16L * 1024 * 1024
    }
}
