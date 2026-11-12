/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A round picture fills its round (9 Nov 2026). An artist row drew its photo fitted, the default, so a wide photo
 * shrank to a strip across the circle (seen on the Artists page in Custom order); the grid crops. Every picture
 * clipped to a circle says how it is scaled.
 */
class RoundPicturesCropTest {

    /** Each `AsyncImage(...)` call in [text], its arguments as written. */
    private fun calls(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var from = 0
        while (true) {
            val at = text.indexOf("AsyncImage(", from)
            if (at < 0) return out
            var depth = 0
            var end = at + "AsyncImage".length
            while (end < text.length) {
                when (text[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            out += text.substring(0, at).count { it == '\n' } + 1 to text.substring(at, end)
            from = end
        }
    }

    @Test
    fun `every picture clipped to a circle is scaled on purpose`() {
        val unscaled = File("src/main/java/com/dd3boh/outertune/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> calls(file.readText()).map { (line, call) -> Triple(file, line, call) } }
            .filter { (_, _, call) -> "CircleShape" in call && "contentScale" !in call }
            .map { (file, line, _) -> "${file.name}:$line" }
            .toList()
        assertTrue("round pictures drawn without a contentScale: $unscaled", unscaled.isEmpty())
    }

    @Test
    fun `the artist row and the artist picker crop`() {
        val row = File("src/main/java/com/dd3boh/outertune/ui/component/items/ArtistItems.kt").readText()
        assertTrue("contentScale = ContentScale.Crop,\n            modifier = Modifier\n                .size(ListThumbnailSize)\n                .clip(CircleShape)" in row)
        val picker = File("src/main/java/com/dd3boh/outertune/ui/dialog/ArtistDialog.kt").readText()
        assertTrue("contentScale = ContentScale.Crop," in picker)
    }
}
