/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A note for next year's you (his add of 22 Nov 2026): typed at the end of the Recap, kept on this
 * phone only, encrypted with a key of the phone's own keystore that never leaves it, and opened by
 * the next year's Recap as "A note from your past self". Nothing is sent and it is not backed up: a
 * reinstall or a new phone loses the key, and the note with it, which the slide says. One note a
 * year, files/recap-notes/note-<year>.bin: the IV's length, the IV, then the ciphertext of [encode]'s text.
 */
object RecapNote {
    /** The longest note kept. */
    const val MAX_CHARS = 500

    private const val ALIAS = "recap-note"
    private const val TAG_BITS = 128

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

    /** In a folder of its own, left out of Android's backups (backup_rules, data_extraction_rules): the key stays here. */
    fun fileOf(context: Context, year: Int): File = File(File(context.filesDir, "recap-notes"), "note-$year.bin")

    /** The keystore's key for notes, made the first time. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Seals [text] as [year]'s note, written on [writtenDay]; false where it could not be kept. Off the main thread. */
    fun save(context: Context, year: Int, text: String, writtenDay: Long): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(encode(Note(text.trim().take(MAX_CHARS), writtenDay)).toByteArray(Charsets.UTF_8))
        val file = fileOf(context, year)
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed)
        check(part.renameTo(file))
    }.isSuccess

    /** [year]'s note, or null where there is none or it can no longer be opened (the key gone with a reinstall). Off the main thread. */
    fun read(context: Context, year: Int): Note? = runCatching {
        val file = fileOf(context, year)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        decode(String(cipher.doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)), Charsets.UTF_8))
    }.getOrNull()
}
