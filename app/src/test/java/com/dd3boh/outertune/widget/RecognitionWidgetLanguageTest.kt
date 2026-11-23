/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import com.dd3boh.outertune.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App language: the recognition widget (plain RemoteViews, not Glance) kept its words after a change, and the
 * launcher looks up a text its layout names in the phone's language, so a language chosen for the app never
 * reached it. The app puts its words in itself when it has a language of its own, and draws it again.
 */
class RecognitionWidgetLanguageTest {

    private val receiver = File("src/main/java/com/dd3boh/outertune/widget/RecognitionWidgetReceiver.kt").readText()
    private val layout = File("src/main/res/layout/widget_recognition.xml").readText()
    private val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()

    @Test
    fun `the words are put in by the app when it has a language of its own`() {
        assertTrue("android:id=\"@+id/widget_recognition_label\"" in layout)
        val views = receiver.substringAfter("private fun views(context: Context): RemoteViews {").substringBefore("fun redraw(")
        assertTrue(views, "val own = AppLanguage.current(context) != SYSTEM_DEFAULT" in views)
        val own = views.substringAfter("if (own) {")
        assertTrue(views, own != views)
        assertTrue("val words = context.getString(R.string.recognise)" in own)
        assertTrue("setTextViewText(R.id.widget_recognition_label, words)" in own)
        assertTrue("setContentDescription(R.id.widget_recognition_button, words)" in own)
        assertTrue("the tap was lost", "setOnClickPendingIntent(R.id.widget_recognition_button, tap)" in views)
    }

    @Test
    fun `back to the phone's language the launcher builds the button afresh, so none of the app's words stay`() {
        // Given the layout it shows, the launcher only runs the new steps on the button it has: the words the app put
        // in stayed after going back to System default. With a language of its own the button is the same layout
        // under another id, so each way across the launcher makes it again.
        val views = receiver.substringAfter("private fun views(context: Context): RemoteViews {").substringBefore("fun redraw(")
        assertTrue(views, "val layout = if (own) R.layout.widget_recognition_app_language else R.layout.widget_recognition" in views)
        assertTrue(views, "RemoteViews(context.packageName, layout)" in views)
        val alias = File("src/main/res/values/layouts_recognition_widget.xml").readText()
        assertTrue(alias, "<item name=\"widget_recognition_app_language\" type=\"layout\">@layout/widget_recognition</item>" in alias)
        assertNotEquals(R.layout.widget_recognition, R.layout.widget_recognition_app_language)
    }

    @Test
    fun `the launcher's update and the app's redraw draw the same button`() {
        val update = receiver.substringAfter("override fun onUpdate(").substringBefore("companion object")
        assertTrue("val views = views(context)" in update)
        val redraw = receiver.substringAfter("fun redraw(context: Context) {")
        assertTrue("getAppWidgetIds(ComponentName(context, RecognitionWidgetReceiver::class.java))" in redraw)
        assertTrue("val views = views(context)" in redraw)
        assertTrue("ids.forEach { manager.updateAppWidget(it, views) }" in redraw)
        // RecognitionShortcutTest: the widget never names the service that listens.
        assertFalse("RecognitionService" in receiver.replace("RecognitionShortcut", ""))
    }

    @Test
    fun `it is drawn again at each change of language and at each start`() {
        val collector = app.substringAfter("AppLanguage.changes.collect {").substringBefore("\n            }\n")
        assertTrue(collector, "runCatching { RecognitionWidgetReceiver.redraw(this@App) }" in collector)
        assertTrue("GlobalScope.launch(Dispatchers.IO) { runCatching { RecognitionWidgetReceiver.redraw(this@App) } }" in app)
    }
}
