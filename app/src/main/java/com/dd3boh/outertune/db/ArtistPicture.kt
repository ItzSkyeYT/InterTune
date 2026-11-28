/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.ui.utils.resize
import com.zionhuang.innertube.pages.ArtistPage

/**
 * An artist's picture in their row. A row made from a song's credits has none, as a credit carries no picture, and
 * the heart on the artist's page only turned the like on: most artists liked there stood in Library > Artists as an
 * empty round (28 Nov 2026, Modjo and Daft Punk, liked after a song of theirs had played). A place that knows the
 * picture gives it to a row that has none (ArtistEntity.withPictureIfNone), and a row that has one keeps it.
 * ArtistPictureTest runs [FILL] on the app's schema.
 */
object ArtistPicture {

    /**
     * The picture an artist's page gives their row: the page's head, asked for at the size Library > Artists asks
     * for it when it fills in a missing one (ArtistsDao.update(artist, artistPage)).
     */
    fun fromPage(page: ArtistPage): String? = page.artist.thumbnail?.resize(544, 544)

    /**
     * The artist :id takes :picture when their row has none, liked or not, so that an artist liked before the heart
     * gave the picture gets it the next time their page opens. A row with a picture keeps it, nothing else in the
     * row is written, so a like turned on or off meanwhile stands, and no row is made for an artist not stored.
     */
    const val FILL = "UPDATE artist SET thumbnailUrl = :picture WHERE id = :id AND thumbnailUrl IS NULL"
}
