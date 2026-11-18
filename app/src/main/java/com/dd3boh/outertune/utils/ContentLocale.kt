/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.Log
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.CountryCodeToName
import com.dd3boh.outertune.constants.LanguageCodeToName
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.kugou.KuGou
import java.util.Locale

/**
 * The language and the country YouTube Music answers in (the "YouTube Music language" and
 * "YouTube Music country" rows), worked out in one place for the start of the app, for both rows
 * and for a change of the app's own language (AppLanguage).
 *
 * They are NOT the app's language: they decide the words YouTube writes (the titles of the rows
 * on Home, song and artist names, the charts), never the words the app writes itself. A row left on
 * System default follows the app: the language of the app, and the country of the app's language
 * when it names one (Android's own App languages page offers "Nederlands (Nederland)"), else the
 * country of the phone. That last step is what keeps a Dutch phone on Dutch charts when its app
 * is switched to plain "English", which has no country of its own.
 */
object ContentLocale {

    /**
     * What YouTube is asked in. [contentLanguage] and [contentCountry] are the two rows as they
     * are stored (SYSTEM_DEFAULT, a code, or nothing yet), [appLocale] the language the app speaks
     * now and [phoneLocale] the phone's own.
     */
    fun of(
        contentLanguage: String?,
        contentCountry: String?,
        appLocale: Locale,
        phoneLocale: Locale = appLocale,
    ): YouTubeLocale {
        val languageTag = appLocale.toLanguageTag().replace("-Hant", "") // zh-Hant-* is zh-*
        // YouTube writes Indonesian and Hebrew as id and iw; Java may say in and he.
        val language = when (val code = appLocale.language) {
            "in" -> "id"
            "he" -> "iw"
            else -> code
        }
        return YouTubeLocale(
            gl = contentCountry?.takeIf { it != SYSTEM_DEFAULT }
                ?: appLocale.country.takeIf { it in CountryCodeToName }
                ?: phoneLocale.country.takeIf { it in CountryCodeToName }
                ?: "US",
            hl = contentLanguage?.takeIf { it != SYSTEM_DEFAULT }
                ?: language.takeIf { it in LanguageCodeToName }
                ?: languageTag.takeIf { it in LanguageCodeToName }
                ?: "en",
        )
    }

    /** True when the lyrics source should be asked for Traditional Chinese: an app in zh-TW. */
    fun traditionalChinese(appLocale: Locale): Boolean =
        appLocale.toLanguageTag().replace("-Hant", "") == "zh-TW"

    /** The language the app speaks right now, as [context] (an activity, a service, the app) sees it. */
    fun appLocale(context: Context): Locale = context.resources.configuration.locales[0]

    /**
     * The phone's own language and region, which the app's own choice never touches. From Android 13
     * an app with a language of its own is handed that language first everywhere in its process,
     * the system's resources included (an app set to English on a Dutch phone reads "en, nl-NL"
     * there, and plain "en" has no country), so the phone's own is asked of LocaleManager, which
     * leaves the app's choice out. Before 13 the app's choice never reaches the system's resources.
     */
    fun phoneLocale(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) {
            val phone = context.getSystemService(LocaleManager::class.java)?.systemLocales
            if (phone != null && !phone.isEmpty) return phone[0]
        }
        return Resources.getSystem().configuration.locales[0]
    }

    /** Sets what YouTube is asked in, for an app that speaks [spoken] with the two rows as stored now. */
    fun setUpFor(context: Context, spoken: Locale) {
        val store = context.dataStore
        val phone = phoneLocale(context)
        YouTube.locale = of(store[ContentLanguageKey], store[ContentCountryKey], spoken, phone)
        KuGou.useTraditionalChinese = traditionalChinese(spoken)
        Log.i(TAG, "YouTube in ${YouTube.locale.hl}, ${YouTube.locale.gl}: the app speaks ${spoken.toLanguageTag()}, " +
            "the phone ${phone.toLanguageTag()}, the process ${Resources.getSystem().configuration.locales.toLanguageTags()}")
    }

    private const val TAG = "ContentLocale"
}
