/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A note for next year's you (his add of 22 Nov 2026): typed at the end of the Recap and opened by
 * the next year's Recap as "A note from your past self". Kept in the app's own files, sealed
 * (AES-GCM), and carried by backups (his "the paper recap needs to come with backups"): the app's own
 * (BackupWriter.FILES) and Android's. The key travels with the notes, so a restored phone opens them;
 * whoever holds a backup file could too, as the library in it. Nothing is ever sent.
 * [NOTES_FILE] holds every year's note; [KEY_FILE] the key, made the first time.
 */
object RecapNote {
    /** The longest note kept. */
    const val MAX_CHARS = 500

    /** Under the app's files: names the app's own backup carries and restores (BackupWriter.FILES). */
    const val KEY_FILE = "recap-notes/key"
    const val NOTES_FILE = "recap-notes/notes.bin"

    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    /** A note and the local day it was written, as an epoch day. */
    data class Note(val text: String, val writtenDay: Long)

    /** The text sealed: the day it was written, a line break, the note. */
    fun encode(note: Note): String = "${note.writtenDay}\n${note.text}"

    /** [encode] read back; null for anything else. */
    fun decode(plain: String): Note? {
        val cut = plain.indexOf('\n')
        if (cut <= 0) return null
        val day = plain.substring(0, cut).toLongOrNull() ?: return null
        return Note(plain.substring(cut + 1), day)
    }

    /** Every year's sealed note in one file: per note its year, then the IV and the sealed text, each after its length. */
    internal fun pack(notes: Map<Int, Pair<ByteArray, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(notes.size)
            for ((year, sealed) in notes.toSortedMap()) {
                out.writeInt(year)
                out.writeInt(sealed.first.size)
                out.write(sealed.first)
                out.writeInt(sealed.second.size)
                out.write(sealed.second)
            }
        }
        return bytes.toByteArray()
    }

    internal fun unpack(bytes: ByteArray): Map<Int, Pair<ByteArray, ByteArray>> = DataInputStream(bytes.inputStream()).use { input ->
        val notes = HashMap<Int, Pair<ByteArray, ByteArray>>()
        repeat(input.readInt()) {
            val year = input.readInt()
            val iv = ByteArray(input.readInt()).also { input.readFully(it) }
            val sealed = ByteArray(input.readInt()).also { input.readFully(it) }
            notes[year] = iv to sealed
        }
        notes
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.writeBytes(bytes)
        check(part.renameTo(file))
    }

    /** The key under [dir], made the first time. */
    private fun key(dir: File): SecretKeySpec {
        val file = File(dir, KEY_FILE)
        val raw = file.takeIf { it.isFile && it.length() == KEY_BYTES.toLong() }?.readBytes()
            ?: ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it); write(file, it) }
        return SecretKeySpec(raw, "AES")
    }

    /** Seals [text] as [year]'s note under [dir], written on [writtenDay], in place of any note of that year. */
    internal fun saveIn(dir: File, year: Int, text: String, writtenDay: Long): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(dir)) }
        val sealed = cipher.doFinal(encode(Note(text.trim().take(MAX_CHARS), writtenDay)).toByteArray(Charsets.UTF_8))
        val file = File(dir, NOTES_FILE)
        val notes = (if (file.isFile) unpack(file.readBytes()) else emptyMap()).toMutableMap()
        notes[year] = cipher.iv to sealed
        write(file, pack(notes))
    }.isSuccess

    /** [year]'s note under [dir], or null where there is none or it cannot be opened. */
    internal fun readIn(dir: File, year: Int): Note? = runCatching {
        val file = File(dir, NOTES_FILE)
        val keyFile = File(dir, KEY_FILE)
        if (!file.isFile || !keyFile.isFile) return null
        val (iv, sealed) = unpack(file.readBytes())[year] ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(keyFile.readBytes(), "AES"), GCMParameterSpec(TAG_BITS, iv)) }
        decode(String(cipher.doFinal(sealed), Charsets.UTF_8))
    }.getOrNull()

    /** Seals [text] as [year]'s note; false where it could not be kept. Off the main thread. */
    fun save(context: Context, year: Int, text: String, writtenDay: Long): Boolean = saveIn(context.filesDir, year, text, writtenDay)

    /** [year]'s note, or null. Off the main thread. */
    fun read(context: Context, year: Int): Note? = readIn(context.filesDir, year)
}
