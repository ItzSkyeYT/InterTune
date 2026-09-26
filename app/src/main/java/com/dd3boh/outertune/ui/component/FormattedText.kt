/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * One piece of a poll's or an announcement's text, as [bodyBlocks] splits it.
 *
 * [bold] ranges index into [text], which no longer has the asterisks that marked them.
 * [gap] is true when a blank line came before this block, which is how paragraphs are written.
 */
sealed interface BodyBlock {
    val text: String
    val bold: List<IntRange>
    val gap: Boolean

    /** Lines written one after another, kept as separate lines. */
    data class Paragraph(override val text: String, override val bold: List<IntRange>, override val gap: Boolean) : BodyBlock

    /** A line starting "- ", "* " or "• ". */
    data class Item(override val text: String, override val bold: List<IntRange>, override val gap: Boolean) : BodyBlock

    /** A line starting "# " or "## ". */
    data class Heading(override val text: String, override val bold: List<IntRange>, override val gap: Boolean) : BodyBlock
}

private val ITEM = Regex("""^\s*[-*•]\s+""")
private val HEADING = Regex("""^\s*#{1,3}\s+""")
private val BOLD = Regex("""\*\*(.+?)\*\*""")

/**
 * The few things a hand-written document can ask for, and nothing more: paragraphs, list items,
 * headings and **bold**. Anything else is shown as typed, so a stray asterisk or hash never makes
 * text vanish. Web addresses are found later, in each block, by [webLinks].
 */
fun bodyBlocks(source: String): List<BodyBlock> {
    val blocks = mutableListOf<BodyBlock>()
    val paragraph = mutableListOf<String>()
    var gap = false
    var paragraphGap = false

    fun flush() {
        if (paragraph.isEmpty()) return
        val (text, bold) = withoutBoldMarks(paragraph.joinToString("\n"))
        blocks += BodyBlock.Paragraph(text, bold, paragraphGap)
        paragraph.clear()
    }

    for (line in source.replace("\r\n", "\n").split('\n')) {
        if (line.isBlank()) {
            flush()
            if (blocks.isNotEmpty()) gap = true
            continue
        }
        val item = ITEM.find(line)
        val heading = HEADING.find(line)
        when {
            item != null -> {
                flush()
                val (text, bold) = withoutBoldMarks(line.substring(item.range.last + 1).trim())
                blocks += BodyBlock.Item(text, bold, gap)
                gap = false
            }
            heading != null -> {
                flush()
                val (text, bold) = withoutBoldMarks(line.substring(heading.range.last + 1).trim())
                blocks += BodyBlock.Heading(text, bold, gap)
                gap = false
            }
            else -> {
                if (paragraph.isEmpty()) {
                    paragraphGap = gap
                    gap = false
                }
                paragraph += line.trimEnd()
            }
        }
    }
    flush()
    return blocks
}

/** [text] with each **pair** of double asterisks taken out, and where the words between them now sit. */
internal fun withoutBoldMarks(text: String): Pair<String, List<IntRange>> {
    val out = StringBuilder()
    val bold = mutableListOf<IntRange>()
    var at = 0
    for (m in BOLD.findAll(text)) {
        out.append(text, at, m.range.first)
        val start = out.length
        out.append(m.groupValues[1])
        bold += start until out.length
        at = m.range.last + 1
    }
    out.append(text, at, text.length)
    return out.toString() to bold
}

/**
 * A poll's or an announcement's text, laid out: paragraphs with room between them, list items
 * with a hanging bullet, headings, bold, and tappable web addresses.
 *
 * Separate Text blocks rather than one string with paragraph styles, so a list item's second line
 * lines up under its first rather than under the bullet, on every Compose version.
 */
@Composable
fun FormattedBody(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { bodyBlocks(text) }
    val rich = remember(blocks, linkColor) {
        blocks.map { block ->
            rich(block, linkColor) { url -> runCatching { uriHandler.openUri(url) } }
        }
    }

    Column(modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(if (block.gap) 12.dp else 4.dp))
            when (block) {
                is BodyBlock.Heading -> Text(
                    text = rich[i],
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is BodyBlock.Item -> Row {
                    Text(text = "•", style = style, color = color, modifier = Modifier.width(18.dp))
                    Text(text = rich[i], style = style, color = color)
                }

                is BodyBlock.Paragraph -> Text(text = rich[i], style = style, color = color)
            }
        }
    }
}

private fun rich(block: BodyBlock, linkColor: Color, open: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        append(block.text)
        for (range in block.bold) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
        }
        for ((range, url) in webLinks(block.text)) {
            addLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    // Wrapped, since a phone with nothing that opens the address would
                    // otherwise throw on the tap.
                    linkInteractionListener = { open(url) },
                ),
                range.first,
                range.last + 1,
            )
        }
    }
