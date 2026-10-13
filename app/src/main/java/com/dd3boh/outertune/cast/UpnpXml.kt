/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The little XML a DLNA sender reads and writes.
 *
 * javax.xml.parsers is in both Android and the JVM, so what reads a renderer's answers is the same
 * code the unit tests run. Renderers send sloppy XML often enough (a stray `&`, a missing
 * namespace), so every reader here also has a plain tag search to fall back on for the few values
 * it needs.
 *
 * Nothing a renderer sends is parsed with a DOCTYPE. Descriptions and SOAP answers never carry one,
 * and refusing it before the parser sees it is the one guard against entity tricks from a hostile
 * device that works the same on Android, whose parser does not take the JDK's feature flags, as on
 * the JVM.
 */
object UpnpXml {
    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
    }

    /** Errors become exceptions, and nothing is printed: a renderer's bad XML is expected, not news. */
    private val quiet = object : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }

    /** The document's root element, or null for anything that is not plain, well-formed XML. */
    fun parse(xml: String): Element? {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
        return runCatching {
            val builder = synchronized(factory) { factory.newDocumentBuilder() }
            builder.setErrorHandler(quiet)
            builder.parse(InputSource(StringReader(xml.trimStart('\uFEFF', ' ', '\r', '\n', '\t')))).documentElement
        }.getOrNull()
    }

    /** An element's name without its prefix, whether or not the parser kept namespaces. */
    fun localName(node: Node): String = node.localName ?: node.nodeName.substringAfter(':')

    fun children(element: Element, name: String): List<Element> {
        val found = ArrayList<Element>()
        val nodes = element.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && localName(node) == name) found += node
        }
        return found
    }

    fun children(element: Element): List<Element> {
        val found = ArrayList<Element>()
        val nodes = element.childNodes
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)?.let { found += it }
        }
        return found
    }

    fun child(element: Element, name: String): Element? = children(element, name).firstOrNull()

    fun childText(element: Element, name: String): String? = child(element, name)?.textContent?.trim()

    /** The first element named [name] anywhere under [element], depth first. */
    fun find(element: Element, name: String): Element? {
        if (localName(element) == name) return element
        for (child in children(element)) find(child, name)?.let { return it }
        return null
    }

    /** Text as it goes in an element or an attribute. */
    fun escape(text: String): String = buildString(text.length + 16) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // Characters XML 1.0 cannot carry at all. A title with one would make the whole
                // request unreadable to the renderer.
                in '\u0000'..'\u0008', '\u000B', '\u000C', in '\u000E'..'\u001F', '\uFFFE', '\uFFFF' -> Unit
                else -> append(char)
            }
        }
    }

    private val entity = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos);")

    /** The reverse of [escape], with numeric references too, for text found by [findText]. */
    fun unescape(text: String): String = entity.replace(text) { match ->
        when (val name = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (name[1] == 'x' || name[1] == 'X') name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
            }
        }
    }

    /**
     * The text of the first element named [name], found by a plain search rather than a parse:
     * the fallback for XML a parser refuses. Any prefix is accepted, CDATA is unwrapped, and an
     * empty element gives an empty string.
     */
    fun findText(xml: String, name: String): String? {
        val tag = Regex.escape(name)
        val pattern = Regex("<(?:[\\w.-]+:)?$tag(?:\\s[^>]*)?(?:/>|>(.*?)</(?:[\\w.-]+:)?$tag\\s*>)", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(xml) ?: return null
        val inner = match.groups[1]?.value ?: return ""
        return textOf(inner)
    }

    /** Every element named [name], found the same way, as its inner markup. */
    fun findAll(xml: String, name: String): List<String> {
        val tag = Regex.escape(name)
        val pattern = Regex("<(?:[\\w.-]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?$tag\\s*>", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(xml).map { it.groupValues[1] }.toList()
    }

    /** Inner markup as text: CDATA unwrapped, entities undone, trimmed. */
    fun textOf(inner: String): String {
        val cdata = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
        val parts = StringBuilder()
        var last = 0
        for (match in cdata.findAll(inner)) {
            parts.append(unescape(inner.substring(last, match.range.first)))
            parts.append(match.groupValues[1])
            last = match.range.last + 1
        }
        parts.append(unescape(inner.substring(last)))
        return parts.toString().trim()
    }
}
