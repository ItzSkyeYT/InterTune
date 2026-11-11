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
 * backport is not there to use. The tag is kept in a small preference file read before anything is
 * drawn ([wrap], from attachBaseContext of the app and of the activity), and the activity is
 * made again on a change. Navigation and what is typed are saved across that, and the music is
 * the service's, which is not touched.
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
            // The app's own context too, which the notification and the widget take their words
            // from: they follow at their next update.
            val locale = if (tags.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tags)
            Locale.setDefault(locale)
            val resources = activity.applicationContext.resources
            val config = Configuration(resources.configuration).apply { setLocale(locale) }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
            activity.recreate()
        }
    }

    /**
     * Before Android 13: [base] in the chosen language, for attachBaseContext. Later Androids
     * hand over a context that already is, and get it back untouched.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = saved(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private fun saved(context: Context): String =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") }.getOrNull().orEmpty()
}
