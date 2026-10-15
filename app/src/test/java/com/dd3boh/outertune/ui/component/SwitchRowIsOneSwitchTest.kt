package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A setting with a switch is one switch for a screen reader: the row toggles and says on or off,
 * and the switch drawn at its end takes no touch of its own. Before, the row was a plain click
 * that read its words but not its state, and the switch beside it read its state with no name
 * (15 Oct 2026: 25 nameless switches on Player and audio alone).
 */
class SwitchRowIsOneSwitchTest {

    private val switchPreference: String = File("src/main/java/com/dd3boh/outertune/ui/component/Preference.kt").readText()
        .substringAfter("fun SwitchPreference(")
        .substringBefore("\n}\n")

    @Test
    fun theRowIsTheSwitch() {
        assertTrue(switchPreference.contains(".toggleable("))
        assertTrue(switchPreference.contains("role = Role.Switch"))
        assertTrue(switchPreference.contains("onValueChange = onCheckedChange"))
    }

    private val explainedSwitch: String = File("src/main/java/com/dd3boh/outertune/ui/component/Explain.kt").readText()
        .substringAfter("fun ExplainedSwitchPreference(")
        .substringBefore("\n)\n")

    @Test
    fun theSwitchWithAnExplanationIsOneSwitchToo() {
        assertTrue(explainedSwitch.contains(".toggleable("))
        assertTrue(explainedSwitch.contains("role = Role.Switch"))
        assertTrue(explainedSwitch.contains("onCheckedChange = null"))
        assertFalse(explainedSwitch.contains("onClick = { onCheckedChange(!checked) }"))
    }

    @Test
    fun theDrawnSwitchTakesNoTouchOfItsOwn() {
        assertTrue(switchPreference.contains("onCheckedChange = null"))
        assertFalse("the row is no plain click any more", switchPreference.contains("onClick = { onCheckedChange(!checked) }"))
    }

    /**
     * Every switch and radio button drawn anywhere in the app leaves the touch to the row it is in,
     * for the same reason. Four did not, outside Settings (Keep listening in Song recognition, a
     * synced playlist, the folders dialog's choice between the two lists, the widget's settings),
     * and read as two things each.
     */
    @Test
    fun everySwitchAndRadioButtonLeavesTheTouchToItsRow() {
        val offenders = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            Regex("""\b(Switch|RadioButton)\(""").findAll(text).mapNotNull { call ->
                val rest = text.substring(call.range.last + 1)
                val args = rest.substring(0, closing(rest))
                val handler = if (call.groupValues[1] == "Switch") "onCheckedChange" else "onClick"
                if (args.contains("$handler = null")) null else "${file.name}: ${call.value}${args.trim().take(80)}"
            }
        }.toList()
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** Where the call whose arguments [rest] starts with ends: its own closing bracket. */
    private fun closing(rest: String): Int {
        var depth = 1
        for (i in rest.indices) {
            when (rest[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return rest.length
    }
}
