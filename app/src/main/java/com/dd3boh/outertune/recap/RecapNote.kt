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
 * Notes for next year's you (his adds of 22 Nov 2026): typed at the end of the Recap and opened by
 * the next year's Recap as "A note from your past self". A year keeps several (his "an option to
 * write multiple notes"), up to [MAX_NOTES], in the order written, each sealed apart (AES-GCM, an
 * IV of its own). Kept in the app's own files and carried by backups (his "the paper recap needs
 * to come with backups"): the app's own (BackupWriter.FILES) and Android's. The key travels with
 * the notes, so a restored phone opens them; whoever holds a backup file could too, as the library
 * in it. Nothing is ever sent. [NOTES_FILE] holds every year's notes; [KEY_FILE] the key, made the
 * first time. A file that cannot be read reads as no notes, and is kept aside when a note is sealed.
 */
object RecapNote {
    /** The longest note kept. */
    const val MAX_CHARS = 500

    /** The most notes a year keeps. */
    const val MAX_NOTES = 10

    /** Under the app's files: names the app's own backup carries and restores (BackupWriter.FILES). */
    const val KEY_FILE = "recap-notes/key"
    const val NOTES_FILE = "recap-notes/notes.bin"

    /** What a notes file begins with ("RCN2"): a file without it holds no notes this app can read. */
    internal const val MAGIC = 0x52434E32

    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    /** Lengths a notes file never comes near: a damaged one asks for no more memory than this. */
    private const val MOST_YEARS = 1_000
    private const val MOST_IV = 64
    private const val MOST_SEALED = 16 * 1024

    /** A note and the local day it was written, as an epoch day. */
    data class Note(val text: String, val writtenDay: Long)

    /** One note as kept: its IV and its sealed text. */
    internal class Envelope(val iv: ByteArray, val sealed: ByteArray)

    /** The text sealed: the day it was written, a line break, the note. */
    fun encode(note: Note): String = "${note.writtenDay}\n${note.text}"

    /** [encode] read back; null for anything else. */
    fun decode(plain: String): Note? {
        val cut = plain.indexOf('\n')
        if (cut <= 0) return null
        val day = plain.substring(0, cut).toLongOrNull() ?: return null
        return Note(plain.substring(cut + 1), day)
    }

    /** Every year's notes in one file: [MAGIC], then per year its notes in the order written, each its IV and sealed text after their lengths. */
    internal fun pack(notes: Map<Int, List<Envelope>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(notes.size)
            for ((year, list) in notes.toSortedMap()) {
                out.writeInt(year)
                out.writeInt(list.size)
                for (note in list) {
                    out.writeInt(note.iv.size)
                    out.write(note.iv)
                    out.writeInt(note.sealed.size)
                    out.write(note.sealed)
                }
            }
        }
        return bytes.toByteArray()
    }

    /** [pack] read back. Throws on anything else: a file cut short, damaged, or of another format. */
    internal fun unpack(bytes: ByteArray): Map<Int, List<Envelope>> = DataInputStream(bytes.inputStream()).use { input ->
        require(input.readInt() == MAGIC) { "not a notes file" }
        val years = input.readInt()
        require(years in 0..MOST_YEARS) { "years: $years" }
        val notes = LinkedHashMap<Int, List<Envelope>>()
        repeat(years) {
            val year = input.readInt()
            val count = input.readInt()
            require(count in 0..MAX_NOTES) { "notes: $count" }
            notes[year] = List(count) { Envelope(chunk(input, MOST_IV), chunk(input, MOST_SEALED)) }
        }
        require(input.read() == -1) { "more after the notes" }
        notes
    }

    private fun chunk(input: DataInputStream, most: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..most) { "length: $size" }
        return ByteArray(size).also { input.readFully(it) }
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

    /** Every year's notes under [dir]: none where there is no file or it cannot be read. */
    private fun envelopes(dir: File): Map<Int, List<Envelope>>? {
        val file = File(dir, NOTES_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching { unpack(file.readBytes()) }.getOrNull()
    }

    /**
     * Seals [text] as one more of [year]'s notes under [dir], written on [writtenDay]. False where it
     * could not be kept, and where the year holds [MAX_NOTES] already. A file that cannot be read
     * is kept aside (as NOTES_FILE.unreadable) rather than written over, and the notes start again.
     */
    internal fun saveIn(dir: File, year: Int, text: String, writtenDay: Long): Boolean = runCatching {
        val file = File(dir, NOTES_FILE)
        val notes = (envelopes(dir) ?: emptyMap<Int, List<Envelope>>().also { file.renameTo(File(file.path + ".unreadable")) }).toMutableMap()
        val kept = notes[year].orEmpty()
        if (kept.size >= MAX_NOTES) return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(dir)) }
        val sealed = cipher.doFinal(encode(Note(text.trim().take(MAX_CHARS), writtenDay)).toByteArray(Charsets.UTF_8))
        notes[year] = kept + Envelope(cipher.iv, sealed)
        write(file, pack(notes))
    }.isSuccess

    /** [year]'s notes under [dir] in the order written: those that open, none where there are none or the file cannot be read. */
    internal fun readIn(dir: File, year: Int): List<Note> {
        val keyFile = File(dir, KEY_FILE)
        if (!keyFile.isFile) return emptyList()
        val kept = envelopes(dir)?.get(year) ?: return emptyList()
        val key = runCatching { SecretKeySpec(keyFile.readBytes(), "AES") }.getOrNull() ?: return emptyList()
        return kept.mapNotNull { note ->
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, note.iv)) }
                decode(String(cipher.doFinal(note.sealed), Charsets.UTF_8))
            }.getOrNull()
        }
    }

    /** How many notes [year] holds under [dir], those that would not open included: what [MAX_NOTES] counts. */
    internal fun countIn(dir: File, year: Int): Int = envelopes(dir)?.get(year)?.size ?: 0

    /** How many notes each year holds under [dir], by the year whose Recap sealed them, none opened. */
    internal fun yearsIn(dir: File): Map<Int, Int> = envelopes(dir).orEmpty().mapValues { it.value.size }

    /** Seals [text] as one more of [year]'s notes; false where it could not be kept or the year is full. Off the main thread. */
    fun save(context: Context, year: Int, text: String, writtenDay: Long): Boolean = saveIn(context.filesDir, year, text, writtenDay)

    /** [year]'s notes in the order written, or none. Off the main thread. */
    fun read(context: Context, year: Int): List<Note> = readIn(context.filesDir, year)

    /** How many notes [year] holds. Off the main thread. */
    fun count(context: Context, year: Int): Int = countIn(context.filesDir, year)

    /** How many notes each year holds, by the year whose Recap sealed them. Off the main thread. */
    fun years(context: Context): Map<Int, Int> = yearsIn(context.filesDir)
}
