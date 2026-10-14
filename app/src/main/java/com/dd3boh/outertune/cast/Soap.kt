/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/** A renderer refused an action: the UPnP error code when it gave one (401 Invalid Action and so on). */
class UpnpFault(val action: String, val code: Int?, val description: String?) :
    CastLinkException("$action was refused" + (code?.let { " ($it${description?.let { d -> ", $d" } ?: ""})" } ?: ""))

/**
 * UPnP control calls: SOAP 1.1 envelopes posted to a service's control url.
 *
 * Arguments are escaped once here. The DIDL-Lite metadata that goes in SetAVTransportURI is itself
 * XML, built and escaped by Didl, so it ends up escaped twice on the wire, which is what renderers
 * expect and the classic thing senders get wrong.
 */
object Soap {
    /** The error codes a sender acts on (UPnP AVTransport:1, section 2.4). */
    const val INVALID_ACTION = 401
    const val INVALID_ARGS = 402
    const val TRANSITION_NOT_AVAILABLE = 701
    const val TRANSPORT_LOCKED = 705

    fun body(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(UpnpXml.escape(serviceType)).append("\">")
        for ((name, value) in args) {
            append('<').append(name).append('>').append(UpnpXml.escape(value)).append("</").append(name).append('>')
        }
        append("</u:").append(action).append('>')
        append("</s:Body></s:Envelope>")
    }

    /** The headers every call carries. Some renderers insist on the quotes, in both places. */
    fun headers(serviceType: String, action: String): List<Pair<String, String>> = listOf(
        "Content-Type" to "text/xml; charset=\"utf-8\"",
        "SOAPACTION" to "\"$serviceType#$action\"",
    )

    sealed interface Answer {
        /** The action's out arguments by name. */
        data class Ok(val values: Map<String, String>) : Answer

        data class Fault(val code: Int?, val description: String?) : Answer
    }

    /**
     * An answer to [action]. A 200 carries `<u:{action}Response>` with one element per out
     * argument; anything else is a fault, with the UPnP error code when the renderer gave one.
     */
    fun parse(status: Int, body: String, action: String): Answer {
        val root = UpnpXml.parse(body)
        val error = root?.let { UpnpXml.find(it, "UPnPError") }
        // The plain search only when the parser gave up: a parsed answer can carry escaped XML
        // (TrackMetaData) that must not be searched as if it were markup.
        val code = if (root != null) error?.let { UpnpXml.childText(it, "errorCode") } else UpnpXml.findText(body, "errorCode")
        if (status == 200 && code == null) {
            val response = root?.let { UpnpXml.find(it, "${action}Response") }
            if (response != null) {
                return Answer.Ok(UpnpXml.children(response).associate { UpnpXml.localName(it) to it.textContent.trim() })
            }
            // A 200 that says nothing readable still did what it was asked; the few renderers that
            // answer Play or Stop with an empty body are otherwise fine.
            return Answer.Ok(UpnpXml.findAll(body, "${action}Response").firstOrNull()?.let(::looseValues).orEmpty())
        }
        val description = if (root != null) error?.let { UpnpXml.childText(it, "errorDescription") } else UpnpXml.findText(body, "errorDescription")
        return Answer.Fault(
            code?.trim()?.toIntOrNull(),
            description?.takeIf { it.isNotBlank() } ?: "HTTP $status",
        )
    }

    private val element = Regex("<(?:[\\w.-]+:)?([\\w.-]+)(?:\\s[^>]*)?(?:/>|>(.*?)</(?:[\\w.-]+:)?\\1\\s*>)", RegexOption.DOT_MATCHES_ALL)

    private fun looseValues(inner: String): Map<String, String> =
        element.findAll(inner).associate { it.groupValues[1] to UpnpXml.textOf(it.groupValues[2]) }
}
