/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Intent
import androidx.annotation.StringRes
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The settings Android Auto shows behind the cog it puts over the app's tabs, since the rework
 * (Unreleased.AUTO_REWORK): the few a driver has a reason to change on the road. Each is one of
 * the phone's own, read and written under the same key, so a switch turned in the car is turned
 * on the phone, and the player follows it as it follows the phone's. The rest stay on the phone.
 */
data class AutoSettings(
    /** More songs are added when the queue runs out. */
    val more: Boolean,
    val quality: AudioQuality,
    /** Every song at about the same loudness. */
    val level: Boolean,
    val silence: Boolean,
) {
    /** The lines of the list, top to bottom. A car cuts a list off, at six lines on most. */
    enum class Line { MORE, QUALITY, LEVEL, SILENCE }

    companion object {
        /** The qualities to choose from, as the phone lists them. */
        val QUALITIES: List<AudioQuality> = AudioQuality.entries

        /** What is saved, with the player's own answer where nothing was ever chosen. */
        fun of(saved: Preferences) = AutoSettings(
            more = saved[AutoLoadMoreKey] ?: true,
            quality = saved[AudioQualityKey].toEnum(AudioQuality.AUTO),
            level = saved[AudioNormalizationKey] ?: true,
            silence = saved[SkipSilenceKey] ?: false,
        )
    }
}

/**
 * What Android Auto binds to for those settings: a screen drawn by the car from a description,
 * the only kind of screen an app is given there. Only a car's own software is let in.
 */
class AutoSettingsService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val known = HostValidator.Builder(applicationContext)
        // With the rework held back nothing points here, and nobody is let in.
        if (Unreleased.AUTO_REWORK) known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
        return known.build()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = AutoSettingsScreen(carContext)
    }
}

/** A choice is written whether or not the screen it was made on is still there. */
private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun <T> CarContext.save(key: Preferences.Key<T>, value: T) {
    val store = applicationContext.dataStore
    writes.launch { store.edit { it[key] = value } }
}

/** A list's title and its way back: the header, or on a car that does not know one, the two it replaced. */
@Suppress("DEPRECATION")
private fun ListTemplate.Builder.titled(car: CarContext, @StringRes title: Int): ListTemplate.Builder =
    if (car.carAppApiLevel >= CarAppApiLevels.LEVEL_7) {
        setHeader(Header.Builder().setTitle(car.getString(title)).setStartHeaderAction(Action.BACK).build())
    } else {
        setTitle(car.getString(title)).setHeaderAction(Action.BACK)
    }

@get:StringRes
private val AudioQuality.label: Int
    get() = when (this) {
        AudioQuality.MAX -> R.string.audio_quality_max
        AudioQuality.AUTO -> R.string.audio_quality_auto
        AudioQuality.HIGH -> R.string.audio_quality_high
        AudioQuality.LOW -> R.string.audio_quality_low
    }

private class AutoSettingsScreen(carContext: CarContext) : Screen(carContext) {
    private var shown: AutoSettings? = null

    init {
        // Followed and not read once: a switch turned on the phone meanwhile shows here too.
        lifecycleScope.launch {
            carContext.dataStore.data.map { AutoSettings.of(it) }.distinctUntilChanged().collect {
                shown = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val list = ListTemplate.Builder().titled(carContext, R.string.settings)
        val now = shown ?: return list.setLoading(true).build()
        val most = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val lines = ItemList.Builder()
        for (line in AutoSettings.Line.entries.take(most)) {
            lines.addItem(
                when (line) {
                    AutoSettings.Line.MORE -> switch(R.string.auto_load_more, now.more, AutoLoadMoreKey)
                    AutoSettings.Line.QUALITY -> Row.Builder()
                        .setTitle(carContext.getString(R.string.audio_quality))
                        .addText(carContext.getString(now.quality.label))
                        .setBrowsable(true)
                        .setOnClickListener { screenManager.push(AutoQualityScreen(carContext)) }
                        .build()
                    AutoSettings.Line.LEVEL -> switch(R.string.audio_normalization, now.level, AudioNormalizationKey)
                    AutoSettings.Line.SILENCE -> switch(R.string.skip_silence, now.silence, SkipSilenceKey)
                }
            )
        }
        return list.setSingleList(lines.build()).build()
    }

    private fun switch(@StringRes title: Int, on: Boolean, key: Preferences.Key<Boolean>) = Row.Builder()
        .setTitle(carContext.getString(title))
        .setToggle(Toggle.Builder { now -> carContext.save(key, now) }.setChecked(on).build())
        .build()
}

/** The qualities, one of them chosen: a tap chooses another and goes back. */
private class AutoQualityScreen(carContext: CarContext) : Screen(carContext) {
    private var chosen: AudioQuality? = null

    init {
        lifecycleScope.launch {
            carContext.dataStore.data.map { AutoSettings.of(it).quality }.distinctUntilChanged().collect {
                chosen = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val list = ListTemplate.Builder().titled(carContext, R.string.audio_quality)
        val now = chosen ?: return list.setLoading(true).build()
        val lines = ItemList.Builder()
        for (quality in AutoSettings.QUALITIES) lines.addItem(Row.Builder().setTitle(carContext.getString(quality.label)).build())
        lines.setSelectedIndex(AutoSettings.QUALITIES.indexOf(now))
        lines.setOnSelectedListener { index ->
            carContext.save(AudioQualityKey, AutoSettings.QUALITIES[index].name)
            screenManager.pop()
        }
        return list.setSingleList(lines.build()).build()
    }
}
