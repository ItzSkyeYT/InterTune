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
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.annotation.DrawableRes
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AdaptiveQueueMode
import com.dd3boh.outertune.constants.AdaptiveQueueModeKey
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.isOn
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
    /** Radios and mixes keep up to date as songs are skipped, finished and liked. */
    val radios: Boolean = true,
) {
    /** The lines of the list, top to bottom, under their headings. A car cuts a list off, at six lines on most. */
    enum class Line { QUALITY, LEVEL, SILENCE, MORE, RADIOS }

    /** The headings, each with its lines: Sound and Playback (26 Oct 2026, as he approved them drawn). */
    enum class Section(@StringRes val title: Int, val lines: List<Line>) {
        SOUND(R.string.auto_settings_sound, listOf(Line.QUALITY, Line.LEVEL, Line.SILENCE)),
        PLAYBACK(R.string.auto_settings_playback, listOf(Line.MORE, Line.RADIOS)),
    }

    companion object {
        /** The qualities to choose from, as the phone lists them. */
        val QUALITIES: List<AudioQuality> = AudioQuality.entries

        /** What is saved, with the player's own answer where nothing was ever chosen. */
        fun of(saved: Preferences) = AutoSettings(
            more = saved[AutoLoadMoreKey] ?: true,
            quality = saved[AudioQualityKey].toEnum(AudioQuality.AUTO),
            level = saved[AudioNormalizationKey] ?: true,
            silence = saved[SkipSilenceKey] ?: false,
            radios = saved[AdaptiveQueueModeKey].toEnum(AdaptiveQueueMode.AUTOPLAY_ONLY).isOn,
        )
    }
}

/**
 * What Android Auto binds to for those settings: a screen drawn by the car from a description,
 * the only kind of screen an app is given there. Only a car's own software is let in.
 */
class AutoSettingsService : CarAppService() {
    override fun createHostValidator(): HostValidator = carHosts(this)

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
        settingsSections(carContext, now, screenManager).forEach { list.addSectionedList(it) }
        return list.build()
    }
}

/** A row's picture: one of the car's white icons, tinted by the car. */
private fun icon(car: CarContext, @DrawableRes id: Int) = CarIcon.Builder(IconCompat.createWithResource(car, id)).build()

/**
 * The settings under their headings, each row with its icon and a line on what it does: for the
 * tab of the app's own screens, and for the screen Android Auto opens from More. A car cuts a list
 * off (six lines on most): rows past its limit are left out, the last heading first.
 */
internal fun settingsSections(car: CarContext, now: AutoSettings, screens: ScreenManager): List<SectionedItemList> {
    var room = car.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    fun switch(@StringRes title: Int, @StringRes line: Int, @DrawableRes picture: Int, on: Boolean, flip: (Boolean) -> Unit) = Row.Builder()
        .setTitle(car.getString(title))
        .addText(car.getString(line))
        .setImage(icon(car, picture), Row.IMAGE_TYPE_ICON)
        .setToggle(Toggle.Builder { chosen -> flip(chosen) }.setChecked(on).build())
        .build()
    fun row(line: AutoSettings.Line): Row = when (line) {
        AutoSettings.Line.QUALITY -> Row.Builder()
            .setTitle(car.getString(R.string.audio_quality))
            .addText(car.getString(now.quality.label))
            .setImage(icon(car, R.drawable.auto_quality), Row.IMAGE_TYPE_ICON)
            .setBrowsable(true)
            .setOnClickListener { screens.push(AutoQualityScreen(car)) }
            .build()
        AutoSettings.Line.LEVEL -> switch(R.string.audio_normalization, R.string.auto_level_line, R.drawable.auto_level, now.level) { car.save(AudioNormalizationKey, it) }
        AutoSettings.Line.SILENCE -> switch(R.string.skip_silence, R.string.auto_silence_line, R.drawable.auto_silence, now.silence) { car.save(SkipSilenceKey, it) }
        AutoSettings.Line.MORE -> switch(R.string.auto_load_more, R.string.auto_more_line, R.drawable.auto_more, now.more) { car.save(AutoLoadMoreKey, it) }
        // As the phone's switch writes it (AdaptiveQueueFrag): on is the default mode, off is off.
        AutoSettings.Line.RADIOS -> switch(R.string.adaptive_queue, R.string.auto_radios_line, R.drawable.auto_radio, now.radios) {
            car.save(AdaptiveQueueModeKey, (if (it) AdaptiveQueueMode.AUTOPLAY_ONLY else AdaptiveQueueMode.OFF).name)
        }
    }
    val sections = mutableListOf<SectionedItemList>()
    for (section in AutoSettings.Section.entries) {
        val lines = section.lines.take(room.coerceAtLeast(0))
        if (lines.isEmpty()) break
        room -= lines.size
        val items = ItemList.Builder()
        lines.forEach { items.addItem(row(it)) }
        sections += SectionedItemList.create(items.build(), car.getString(section.title))
    }
    return sections
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
