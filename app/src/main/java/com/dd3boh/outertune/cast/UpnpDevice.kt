/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.w3c.dom.Element
import java.net.URI

/** One UPnP service of a device: its exact type, which every SOAP call must name, and its control url. */
data class UpnpService(val serviceType: String, val controlUrl: String, val eventSubUrl: String?)

/**
 * A media renderer as its description tells it: who it is, and the three services a sender needs.
 * Only AVTransport is required; without RenderingControl the volume stays the device's own, and
 * without ConnectionManager the renderer never says what it plays.
 */
data class DlnaRenderer(
    val location: String,
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val avTransport: UpnpService,
    val renderingControl: UpnpService?,
    val connectionManager: UpnpService?,
)

/**
 * Reads a UPnP device description (the xml at an SSDP LOCATION) into a [DlnaRenderer].
 *
 * The renderer can be the root device or one embedded in it: an AV receiver, for one, often lists
 * a MediaServer and a MediaRenderer side by side. The first device, depth first, that has an
 * AVTransport service is the one taken. Its urls are relative to URLBase when the description has
 * one, as UPnP 1.0 devices do, and to the description's own url otherwise.
 */
object UpnpDevice {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:"

    fun parse(xml: String, location: String): DlnaRenderer? =
        UpnpXml.parse(xml)?.let { parseDocument(it, location) } ?: parseLoosely(xml, location)

    private fun parseDocument(root: Element, location: String): DlnaRenderer? {
        val base = UpnpXml.childText(root, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        val rootDevice = UpnpXml.child(root, "device") ?: return null
        val renderer = firstWithAvTransport(rootDevice) ?: return null
        val services = UpnpXml.child(renderer, "serviceList")?.let { UpnpXml.children(it, "service") }.orEmpty().mapNotNull { service ->
            val type = UpnpXml.childText(service, "serviceType") ?: return@mapNotNull null
            val control = UpnpXml.childText(service, "controlURL")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            UpnpService(type, resolve(base, control), UpnpXml.childText(service, "eventSubURL")?.takeIf { it.isNotBlank() }?.let { resolve(base, it) })
        }
        return build(
            location = location,
            udn = UpnpXml.childText(renderer, "UDN") ?: UpnpXml.childText(rootDevice, "UDN"),
            friendlyName = UpnpXml.childText(renderer, "friendlyName") ?: UpnpXml.childText(rootDevice, "friendlyName"),
            manufacturer = UpnpXml.childText(renderer, "manufacturer"),
            modelName = UpnpXml.childText(renderer, "modelName"),
            services = services,
        )
    }

    private fun firstWithAvTransport(device: Element): Element? {
        val services = UpnpXml.child(device, "serviceList")?.let { UpnpXml.children(it, "service") }.orEmpty()
        if (services.any { UpnpXml.childText(it, "serviceType")?.startsWith(AV_TRANSPORT) == true }) return device
        val embedded = UpnpXml.child(device, "deviceList")?.let { UpnpXml.children(it, "device") }.orEmpty()
        for (child in embedded) firstWithAvTransport(child)?.let { return it }
        return null
    }

    /**
     * The same, by plain search, for a description the parser refused. A device's name comes
     * before its services, so the renderer's is the last one written before its AVTransport, which
     * is right whether it is the root device or one embedded after it.
     */
    private fun parseLoosely(xml: String, location: String): DlnaRenderer? {
        val base = UpnpXml.findText(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        val services = UpnpXml.findAll(xml, "service").mapNotNull { service ->
            val type = UpnpXml.findText(service, "serviceType") ?: return@mapNotNull null
            val control = UpnpXml.findText(service, "controlURL")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            UpnpService(type, resolve(base, control), UpnpXml.findText(service, "eventSubURL")?.takeIf { it.isNotBlank() }?.let { resolve(base, it) })
        }
        val avTransportAt = xml.indexOf(AV_TRANSPORT).takeIf { it >= 0 } ?: return null
        val before = xml.substring(0, avTransportAt)
        fun last(name: String) = UpnpXml.findAll(before, name).lastOrNull()?.let(UpnpXml::textOf)
        return build(
            location = location,
            udn = last("UDN"),
            friendlyName = last("friendlyName"),
            manufacturer = last("manufacturer"),
            modelName = last("modelName"),
            services = services,
        )
    }

    private fun build(
        location: String,
        udn: String?,
        friendlyName: String?,
        manufacturer: String?,
        modelName: String?,
        services: List<UpnpService>,
    ): DlnaRenderer? {
        fun service(prefix: String) = services.firstOrNull { it.serviceType.startsWith(prefix) }
        val avTransport = service(AV_TRANSPORT) ?: return null
        val host = runCatching { URI(location).host }.getOrNull()
        return DlnaRenderer(
            location = location,
            udn = udn?.takeIf { it.isNotBlank() } ?: "location:$location",
            friendlyName = friendlyName?.takeIf { it.isNotBlank() } ?: modelName?.takeIf { it.isNotBlank() } ?: host ?: "Renderer",
            manufacturer = manufacturer?.takeIf { it.isNotBlank() },
            modelName = modelName?.takeIf { it.isNotBlank() },
            avTransport = avTransport,
            renderingControl = service(RENDERING_CONTROL),
            connectionManager = service(CONNECTION_MANAGER),
        )
    }

    /**
     * [url] against [base]. A base with no path at all (`http://host:port`, which URLBase often
     * is) is given one first, since java.net.URI would otherwise glue a relative path straight
     * onto the port.
     */
    fun resolve(base: String, url: String): String {
        val trimmed = url.trim()
        return runCatching {
            var baseUri = URI(base.trim())
            if (baseUri.rawPath.isNullOrEmpty()) baseUri = URI("$baseUri/")
            baseUri.resolve(URI(trimmed.replace(" ", "%20"))).toString()
        }.getOrElse { trimmed }
    }
}
