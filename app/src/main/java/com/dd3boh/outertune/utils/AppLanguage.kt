/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/**
 * The language of the app's own words, chosen in Settings > Library and content > App language.
 *
 * On Android 13 and later the choice is the platform's: it is written with
 * LocaleManager.setApplicationLocales and read back from there, so the row and the phone's own
 * App languages page (Settings > Apps > InterTune > Language) are one setting and can never say
 * different things. The platform then draws the activity again in the new language by itself.
 *
 * Before Android 13 there is no such place, and MainActivity is a ComponentActivity, so AppCompat's
 * backport is not there to use (it only reaches AppCompatActivity screens anyway, never the
 * notification, the widget or a service, which are the other half of "half Dutch, half English").
 * The tag is kept in a small preference file. Screens take it in attachBaseContext ([wrap]) and
 * are made again on a change; everything else (the services, the receivers of the widgets, the
 * workers, the notifications) reads the app's own resources, which [applyToProcess] puts in the
 * chosen language at the start of the process and again after every change of the phone's
 * configuration, which would otherwise put them back to the phone's language. Navigation and what
 * is typed are saved across the screen being made again, and the music is the service's, which is
 * not touched.
 *
 * On every Android, [follow] is what notices the language the app really speaks (however it was
 * picked: this row, Android's own page, the phone's language changing) and sets the rest to it.
 */
object AppLanguage {

    /**
     * Every language the app carries words for, as the generated locale config names them (the
     * list behind Android's App languages page): AppLanguageTest holds it to the values folders.
     */
    val TAGS = listOf(
        "en", "ar", "az", "be", "bg", "bn", "bn-IN", "bs", "ca", "cs", "de", "el", "en-CA", "es",
        "es-US", "et", "fa", "fi", "fil", "fr", "hi", "hr", "hu", "in", "it", "iw", "ja", "ko",
        "ml", "nb-NO", "nl", "pa", "pl", "pt", "pt-BR", "ro", "ru", "sk", "sl", "sr", "sr-Latn",
        "sv", "ta", "te", "th", "tr", "uk", "vi", "zh-CN", "zh-TW",
    )

    private const val FILE = "app_language"
    private const val KEY = "tag"

    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Said each time the language the app speaks is not the one it spoke a moment ago, after YouTube
     * has been set up for the new one: whatever was built with words (Home's rows, the media
     * notification's buttons, the widget) is made again from it.
     */
    val changes: SharedFlow<Unit> = changed.asSharedFlow()

    /** The language [follow] last saw, as a tag. Nothing before the app has started. */
    @Volatile
    private var followed: String? = null

    /** What the platform is handed for a choice: nothing at all for the phone's own language. */
    fun languageTags(choice: String): String = if (choice in TAGS) choice else ""

    /**
     * The choice of the list a language the platform reports stands for. Android's page offers
     * regions of a language and writes scripts out, so fr-FR is our French and zh-Hant-TW our
     * zh-TW; and Java writes Indonesian and Hebrew as id and he where the folders say in and iw.
     */
    fun choiceFor(tag: String?): String {
        if (tag.isNullOrEmpty()) return SYSTEM_DEFAULT
        val parts = tag.split('-')
        val language = when (val l = parts[0].lowercase()) {
            "id" -> "in"
            "he" -> "iw"
            else -> l
        }
        val script = parts.drop(1).firstOrNull { it.length == 4 }
        val region = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
        return listOfNotNull(
            script?.let { "$language-$it" },
            region?.let { "$language-$it" },
            language,
        ).firstOrNull { it in TAGS } ?: SYSTEM_DEFAULT
    }

    /** A language of the list in its own words: "Français", "Deutsch". */
    fun nameOf(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** The choice as it stands: SYSTEM_DEFAULT, or one of [TAGS]. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            choiceFor(if (locales == null || locales.isEmpty) null else locales[0].toLanguageTag())
        } else {
            choiceFor(saved(context))
        }

    /** Switches the app to [choice] at once: the screen stays where it is and the music goes on. */
    fun set(activity: Activity, choice: String) {
        val tags = languageTags(choice)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(tags)
        } else {
            if (tags == saved(activity)) return
            activity.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, tags).apply()
            // The app's own resources too, which the notification, the widget and the services take
            // their words from: they follow at once, and again after each configuration change. Back
            // to the phone's language, the phone's whole list, so Android takes the first one the app
            // has words for, as everywhere else: its first language alone fell to English when the
            // app has none of it.
            val locales = if (tags.isEmpty()) Resources.getSystem().configuration.locales else LocaleList(Locale.forLanguageTag(tags))
            applyInPlace(activity.applicationContext.resources, locales)
            activity.recreate()
        }
    }

    /**
     * Takes the language the app speaks as seen from [context] (an activity as it is made, the app
     * at its start and at every configuration change): YouTube is asked in it, unless the two
     * content rows say otherwise ([ContentLocale]), and when it is not the language of a moment
     * ago, [changes] says so. This is the one place that learns of a change, so a language picked
     * on Android's own App languages page, where the platform only makes the screen again, is taken
     * exactly as one picked in Settings.
     */
    fun follow(context: Context) {
        val locale = ContentLocale.appLocale(context)
        val tag = locale.toLanguageTag()
        val before = followed
        if (tag == before) return
        followed = tag
        ContentLocale.setUpFor(context, locale)
        if (before != null) changed.tryEmit(Unit)
    }

    /**
     * Before Android 13: the app's own resources (not a screen's) in the chosen language. Android
     * puts them back to the phone's language at every change of the phone's configuration (the
     * screen turning, the font size, night mode), so this runs when the process starts and again
     * from Application.onConfigurationChanged. Later Androids do all of it themselves.
     */
    fun applyToProcess(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val tag = saved(context)
        if (tag.isEmpty()) return
        applyInPlace(context.resources, LocaleList(Locale.forLanguageTag(tag)))
    }

    /**
     * Before Android 13, right after a WebView is made (the two sign-in pages, the web client's
     * token and its challenge): the first one of the process adds WebView's own resources to the
     * app's, which makes them again in the phone's language, so the notifications and the widgets
     * spoke it until the screen turned. The app's language is put back at once.
     */
    fun afterWebView(context: Context) = applyToProcess(context.applicationContext)

    @Suppress("DEPRECATION")
    private fun applyInPlace(resources: Resources, locales: LocaleList) {
        if (!locales.isEmpty) LocaleList.setDefault(locales)
        val config = Configuration(resources.configuration).apply { setLocales(locales) }
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * Before Android 13: [base] in the chosen language, for attachBaseContext. Later Androids
     * hand over a context that already is, and get it back untouched.
     *
     * The override names the language and nothing else. A copy of the whole configuration froze
     * everything in it (which way up the phone was, the screen's size): MainActivity is not made
     * again when the phone turns, so on Android 7 to 9 the screen kept its portrait shape on its
     * side and the landscape player never showed.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = saved(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration().apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private fun saved(context: Context): String =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") }.getOrNull().orEmpty()
}
