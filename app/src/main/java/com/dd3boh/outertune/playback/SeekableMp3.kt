/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor

/**
 * The extractors the player reads local files with. A variable-bitrate MP3 with no Xing or VBRI
 * header has no table of where each second sits, so by default media3 calls it unseekable and
 * Android guesses its length from the first frames (48 s for a 3-minute song). With index seeking
 * the extractor builds that table as it reads, so the song can be sought and its length is right.
 *
 * Index seeking costs a read: to seek forward to a place not yet read, the extractor reads on from
 * the end of what it has read up to that place, so a jump far into a long file reads everything
 * before it once (a local file, so quick). YouTube's streams are not MP3 and are unaffected.
 */
@OptIn(UnstableApi::class)
fun seekableMp3Extractors(): DefaultExtractorsFactory =
    DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
