/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gate's code field, read from its source: the gate is in the preview build type, which the unit
 * tests do not compile, and the emulator is where it was seen (experiments/features/tester-code).
 */
class TesterGateTest {

    private val gate = File("src/preview/java/com/dd3boh/outertune/testerlock/TesterGate.kt").readText()
    private val lock = File("src/preview/java/com/dd3boh/outertune/testerlock/TesterLock.kt").readText()

    @Test
    fun `the answer under the field goes as soon as the code is changed`() {
        // Every change of the typed text after the first value, not a move of the cursor and not the
        // code the gate was opened with.
        assertTrue("snapshotFlow { field.text.toString() }.drop(1).collect { onEdited() }" in gate)
        assertTrue("onEdited = session::edited" in lock)
        val edited = lock.substringAfter("fun edited() {").substringBefore("\n    }\n")
        assertTrue(edited, "shown.value = gate.copy(message = null)" in edited)
        // Not while a check is under way: its answer is still to come. And not the answer about the
        // build, out of date: another code does not change that.
        assertTrue(edited, "if (gate.busy || gate.message == null || gate.message == GateMessage.OUTDATED) return" in edited)
    }

    @Test
    fun `every message the rules can give has its own words on the gate`() {
        val messages = Regex("enum class GateMessage \\{([^}]*)}").find(
            File("src/preview/jvm/com/dd3boh/outertune/testerlock/TesterLockRules.kt").readText(),
        )!!.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("UNKNOWN", "REVOKED", "IN_USE", "OUTDATED", "GRACE_OVER"), messages)
        val words = gate.substringAfter("private fun GateMessage.words(): Int = when (this) {").substringBefore("\n}\n")
        for (message in messages) assertTrue(message, "GateMessage.$message -> R.string." in words)
        assertTrue(words, "GateMessage.IN_USE -> R.string.tester_lock_in_use" in words)
        assertTrue(words, "GateMessage.OUTDATED -> R.string.tester_lock_outdated" in words)
        // Only a code the door refused is red, in the field it is under. The two that say the build or the
        // device is the trouble have no field now, and the gate marks none of them as the code's fault.
        val red = gate.substringAfter("isError = ").substringBefore(",\n")
        assertTrue(red, "GateMessage.UNKNOWN" in red && "GateMessage.REVOKED" in red)
        assertFalse(red, "GateMessage.IN_USE" in red || "GateMessage.OUTDATED" in red || "GRACE_OVER" in red)
    }

    @Test
    fun `a good code the door refused this build with is shown in the field`() {
        assertTrue(
            "val keepsCode = after.message == GateMessage.OUTDATED || after.message == GateMessage.IN_USE" in lock,
        )
        assertTrue("GateState(prefill = if (keepsCode) code else \"\")" in lock)
    }

    @Test
    fun `the install number is made and written down before the door is first asked`() {
        val check = lock.substringAfter("private fun check(code: String) {").substringBefore("\n    }\n")
        val made = check.indexOf("TesterLockRules.withInstall(loaded) { TesterInstall.make() }")
        val saved = check.indexOf("if (kept != loaded) store.save(kept)")
        val asked = check.indexOf("door.ask(code, build, BuildConfig.VERSION_CODE, kept.install.orEmpty())")
        assertTrue(check, made in 0 until saved && saved < asked)
        // The answer is kept on top of that memory, so the number is never lost to the answer.
        assertTrue(check, "TesterLockRules.afterCheck(build, kept, code, answer," in check)
    }

    @Test
    fun `an older file loads without the new fields, and the store keeps every one`() {
        val store = lock.substringAfter("internal class TesterLockStore").substringBefore("private companion object")
        // A file saved before the time of the last yes and the install number existed: no number, due at once.
        assertTrue(store, "openedAt = prefs.getLong(OPENED_AT, 0L)" in store)
        assertTrue(store, "install = prefs.getString(INSTALL, null)" in store)
        assertTrue(store, "claimed = prefs.getBoolean(CLAIMED, false)" in store)
        assertTrue(store, "refusedBuild = prefs.getString(REFUSED_BUILD, null)" in store)
        for (field in listOf("code", "install", "openedBuild", "openedAt", "claimed", "graceBuild", "graceSince", "refusedBuild")) {
            val key = field.replace(Regex("[A-Z]")) { "_" + it.value }.uppercase()
            assertTrue("$field is saved", Regex("put\\w+\\($key, memory\\.$field\\)").containsMatchIn(store))
            assertTrue("$field is loaded", Regex("$field = prefs\\.get\\w+\\($key,").containsMatchIn(store))
        }
    }

    @Test
    fun `the keyboard offers no words for a code, and the code stays in sight`() {
        val options = gate.substringAfter("keyboardOptions = KeyboardOptions(").substringBefore("),")
        // To the keyboard a password field: Gboard and the others then suggest nothing and learn nothing.
        assertEquals(listOf("Password"), Regex("keyboardType = KeyboardType\\.(\\w+)").findAll(options).map { it.groupValues[1] }.toList())
        assertTrue(options, "autoCorrectEnabled = false" in options)
        // In Compose only a transformation hides text: the field draws the code itself, and nothing masks it.
        assertTrue("outputTransformation = CodeAsSent" in gate)
        for (masking in listOf("SecureTextField", "TextObfuscationMode", "PasswordVisualTransformation", "visualTransformation")) {
            assertFalse(masking, masking in gate)
        }
        // And what is typed is never changed under the keyboard (the crash of 29 Oct).
        assertFalse("inputTransformation" in gate)
    }

    @Test
    fun `what each answer shows is the screen description's, and the gate decides nothing`() {
        assertTrue("val screen = TesterGateScreen.of(state.message)" in gate)
        assertTrue("if (screen.field) CodeForm(state, screen, field, onOpen) else TestersForm(activity, screen, field, onAnotherCode)" in gate)
        // The field is drawn in one place only, and not by the form that has none.
        assertEquals(1, Regex("OutlinedTextField\\(").findAll(gate).count())
        val testers = gate.substringAfter("private fun TestersForm(").substringBefore("\n}\n")
        assertFalse(testers, "OutlinedTextField" in testers)
        // With no field to enter a code in, the answer is the lead under the title, in place of asking for one.
        assertTrue("val lead = if (screen.field) R.string.tester_lock_text else screen.message?.words() ?: R.string.tester_lock_text" in gate)
        assertTrue("if (screen.help)" in gate)
    }

    @Test
    fun `paste is a text button at the end of the field, shown while the field is empty`() {
        val field = gate.substringAfter("OutlinedTextField(").substringBefore("outputTransformation")
        assertTrue(field, "trailingIcon = if (TesterGateScreen.pasteShown(screen, field.text.isEmpty(), state.busy)) {" in field)
        assertTrue(field, "TextButton(onClick = { scope.launch { pasteInto(field, clipboard) } })" in field)
        assertTrue(field, "Text(stringResource(R.string.tester_lock_paste))" in field)
        assertTrue("else null" in field)
    }

    @Test
    fun `the clipboard is read once, from a tap on paste, and never on its own`() {
        assertEquals(1, Regex("getClipEntry\\(").findAll(gate).count())
        assertEquals(1, Regex("LocalClipboard\\.current").findAll(gate).count())
        // Only the tap calls the function that reads it, and nothing that runs by itself touches it.
        assertEquals(listOf("scope.launch { pasteInto(field, clipboard) }"), Regex("scope\\.launch \\{[^}]*pasteInto[^}]*}").findAll(gate).map { it.value }.toList())
        assertEquals("one call, beside its definition", 2, Regex("pasteInto\\(").findAll(gate).count())
        val read = gate.substringAfter("private suspend fun pasteInto(").substringBefore("\n}\n")
        assertTrue(read, "getClipEntry()" in read)
        for (alone in listOf("LaunchedEffect", "DisposableEffect", "SideEffect", "produceState", "init {")) {
            assertFalse("$alone does not read it", alone in read)
        }
        val lines = gate.lines()
        for ((i, line) in lines.withIndex()) {
            if ("pasteInto(" in line && "private suspend fun" !in line) assertTrue("only inside the tap: $line", "onClick = {" in line)
            if ("getClipEntry" in line) assertTrue(line, lines.take(i).any { "private suspend fun pasteInto(" in it })
        }
        // As text, one item: no manager of the old kind, and nothing coerced from a link or an intent.
        for (other in listOf("ClipboardManager", "primaryClip", "coerceToText", "addPrimaryClipChangedListener")) assertFalse(other, other in gate + lock)
        // What was read goes in the field the way a paste does, drawn as typing is, and an empty paste changes nothing.
        assertTrue("val code = TesterCode.fromPasted(pasted.orEmpty())" in read)
        assertTrue("if (code.isNotEmpty()) field.setTextAndPlaceCursorAtEnd(code)" in read)
    }

    @Test
    fun `open testers opens the channel as any link, and says nothing to anyone`() {
        assertTrue("private const val TESTERS_CHANNEL = \"https://discord.com/channels/1544002995887411230/1559515871305666560\"" in gate)
        val open = gate.substringAfter("private fun openTesters(").substringBefore("\n}\n")
        // The phone picks the Discord app when it is installed and the browser when it is not.
        assertTrue(open, "activity.startActivity(Intent(Intent.ACTION_VIEW, TESTERS_CHANNEL.toUri()))" in open)
        assertTrue(open, "catch (e: ActivityNotFoundException)" in open)
        assertEquals("the one link in the gate", 1, Regex("https?://").findAll(gate).count())
        val testers = gate.substringAfter("private fun TestersForm(").substringBefore("\n}\n")
        assertTrue(testers, "onClick = { openTesters(activity) }" in testers)
        assertTrue(testers, "Text(stringResource(R.string.tester_lock_open_testers))" in testers)
    }

    @Test
    fun `the quiet line and the other code button are the screen description's to show`() {
        val testers = gate.substringAfter("private fun TestersForm(").substringBefore("\n}\n")
        assertTrue(testers, "if (screen.codeIsFine) {" in testers && "R.string.tester_lock_code_fine" in testers)
        assertTrue(testers, "if (screen.anotherCode) {" in testers && "R.string.tester_lock_other_code" in testers)
        // "Use another code" brings the field back empty: the code in it is the one that is in use.
        assertTrue(testers, "TextButton(onClick = { field.clearText(); onAnotherCode() })" in testers)
        assertTrue("onAnotherCode = session::useAnotherCode" in lock)
        val another = lock.substringAfter("fun useAnotherCode() {").substringBefore("\n    }\n")
        assertTrue(another, "if (gate.busy) return" in another && "shown.value = gate.copy(message = null)" in another)
    }

    @Test
    fun `the new words are as they were given, in English and French, with no dash standing in for a comma`() {
        fun strings(path: String) = Regex("<string name=\"(tester_lock_\\w+)\">(.*?)</string>").findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val en = strings("src/preview/res/values/strings-tester.xml")
        val fr = strings("src/preview/res/values-fr/strings-tester.xml")
        val given = mapOf(
            "tester_lock_paste" to ("Paste" to "Coller"),
            "tester_lock_open_testers" to ("Open #testers" to "Ouvrir #testers"),
            "tester_lock_code_fine" to ("Your code is fine: only the build needs updating." to "Votre code est bon : seule la version doit être mise à jour."),
            "tester_lock_other_code" to ("Use another code" to "Utiliser un autre code"),
            "tester_lock_outdated" to ("This test build is out of date. Download the newest one in #testers on Discord." to
                "Cette version de test n\\'est plus à jour. Téléchargez la plus récente dans #testers sur Discord."),
            "tester_lock_in_use" to ("This code is already in use on another device. To move it here, use /code in #testers on Discord." to
                "Ce code est déjà utilisé sur un autre appareil. Pour le transférer ici, utilisez /code dans #testers sur Discord."),
        )
        for ((name, words) in given) {
            assertEquals(name, words.first, en[name])
            assertEquals(name, words.second, fr[name])
        }
        for (text in en.values + fr.values) {
            assertFalse(text, "\u2014" in text || "\u2013" in text)
        }
    }

    @Test
    fun `back at the gate for a code another device holds, the lock looks again behind it and for nothing else`() {
        // Registered with the activity's lifecycle and taken off again with it.
        assertTrue("if (event == Lifecycle.Event.ON_RESUME) session.resumed()" in lock)
        assertTrue("activity.lifecycle.addObserver(observer)" in lock)
        assertTrue("onDispose { activity.lifecycle.removeObserver(observer) }" in lock)
        val resumed = lock.substringAfter("fun resumed() {").substringBefore("\n    }\n")
        // Only at that gate, and not while a check is under way.
        assertTrue(resumed, "if (gate.busy || gate.message != GateMessage.IN_USE || checking?.isActive == true) return" in resumed)
        // With the code kept, through the check that is always made: the same request, the same rules.
        assertTrue(resumed, "val code = store.load().code ?: return" in resumed)
        assertTrue(resumed, "check(code)" in resumed)
        assertFalse("the gate is not drawn busy for it", "busy = true" in resumed)
    }
}
