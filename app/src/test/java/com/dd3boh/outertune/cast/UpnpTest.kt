/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.concurrent.thread

/** The XML, descriptions, SOAP, SSDP and HTTP a DLNA sender speaks, each on its own. */
class UpnpTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    // XML

    @Test
    fun `escaping covers markup, quotes, and characters XML cannot carry`() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", UpnpXml.escape("a & b <c> \"d\" 'e'"))
        assertEquals("tab\tnew\nline\r", UpnpXml.escape("tab\tnew\nline\r"))
        assertEquals("ab", UpnpXml.escape("a\u0000\u0007\u001Fb"))
        assertEquals("caf\u00E9 \u266B", UpnpXml.escape("caf\u00E9 \u266B"))
    }

    @Test
    fun `unescaping takes named and numeric references, and leaves unknown ones`() {
        assertEquals("<a & 'b'>", UpnpXml.unescape("&lt;a &amp; &apos;b&apos;&gt;"))
        assertEquals("\u00E9\u266B", UpnpXml.unescape("&#233;&#x266B;"))
        assertEquals("&nbsp; &#xZZ;", UpnpXml.unescape("&nbsp; &#xZZ;"))
        assertEquals("&lt;", UpnpXml.unescape("&amp;lt;"))
    }

    @Test
    fun `the plain search finds prefixed, empty and CDATA elements`() {
        val xml = "<r><s:v>x &amp; y</s:v><e/><f attr=\"1\"></f><c><![CDATA[<b>&amp;</b>]]> tail</c></r>"
        assertEquals("x & y", UpnpXml.findText(xml, "v"))
        assertEquals("", UpnpXml.findText(xml, "e"))
        assertEquals("", UpnpXml.findText(xml, "f"))
        assertEquals("<b>&amp;</b> tail", UpnpXml.findText(xml, "c"))
        assertNull(UpnpXml.findText(xml, "missing"))
        assertEquals(listOf("1", "2"), UpnpXml.findAll("<a><i>1</i><i x='y'>2</i><item>3</item></a>", "i"))
    }

    @Test
    fun `a document with a DOCTYPE is never parsed`() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;">]><r>&b;</r>"""
        assertNull(UpnpXml.parse(bomb))
        assertNull(UpnpXml.parse("""<!doctype r SYSTEM "file:///etc/passwd"><r/>"""))
        assertNotNull(UpnpXml.parse("\uFEFF<?xml version=\"1.0\"?>\n<r/>"))
        assertNull(UpnpXml.parse("<r><unclosed></r>"))
        assertNull(UpnpXml.parse(""))
    }

    // Descriptions

    private val location = "http://192.168.1.40:49152/description.xml"

    private fun service(type: String, name: String, control: String) =
        "<service><serviceType>urn:schemas-upnp-org:service:$type</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:$name</serviceId><SCPDURL>/$name.xml</SCPDURL>" +
            "<controlURL>$control</controlURL><eventSubURL>/evt/$name</eventSubURL></service>"

    private val renderer = """<?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room TV</friendlyName>
            <manufacturer>Samsung</manufacturer>
            <modelName>UE55</modelName>
            <UDN>uuid:1234-abcd</UDN>
            <serviceList>
              ${service("RenderingControl:1", "RenderingControl", "/upnp/control/RenderingControl1")}
              ${service("ConnectionManager:1", "ConnectionManager", "upnp/control/ConnectionManager1")}
              ${service("AVTransport:1", "AVTransport", "/upnp/control/AVTransport1")}
            </serviceList>
          </device>
        </root>"""

    @Test
    fun `a renderer's description, with its urls made whole`() {
        val found = UpnpDevice.parse(renderer, location)!!
        assertEquals("Living Room TV", found.friendlyName)
        assertEquals("uuid:1234-abcd", found.udn)
        assertEquals("Samsung", found.manufacturer)
        assertEquals("UE55", found.modelName)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", found.avTransport.serviceType)
        assertEquals("http://192.168.1.40:49152/upnp/control/AVTransport1", found.avTransport.controlUrl)
        assertEquals("http://192.168.1.40:49152/evt/AVTransport", found.avTransport.eventSubUrl)
        assertEquals("http://192.168.1.40:49152/upnp/control/RenderingControl1", found.renderingControl!!.controlUrl)
        // Relative without a slash: against the description's folder, here the root.
        assertEquals("http://192.168.1.40:49152/upnp/control/ConnectionManager1", found.connectionManager!!.controlUrl)
    }

    @Test
    fun `urls are relative to URLBase when there is one, even one with no path`() {
        val withBase = renderer.replace("<specVersion>", "<URLBase>http://192.168.1.40:9197</URLBase><specVersion>")
            .replace("/upnp/control/AVTransport1", "dmr/control_2")
        val found = UpnpDevice.parse(withBase, "http://192.168.1.40:7676/other/desc.xml")!!
        assertEquals("http://192.168.1.40:9197/dmr/control_2", found.avTransport.controlUrl)
        assertEquals("http://192.168.1.40:9197/upnp/control/RenderingControl1", found.renderingControl!!.controlUrl)
    }

    @Test
    fun `urls resolve in the other forms devices use`() {
        assertEquals("http://h:1/a/b/ctl", UpnpDevice.resolve("http://h:1/a/b/desc.xml", "ctl"))
        assertEquals("http://h:1/ctl", UpnpDevice.resolve("http://h:1/a/b/desc.xml", "/ctl"))
        assertEquals("http://other:2/ctl", UpnpDevice.resolve("http://h:1/desc.xml", "http://other:2/ctl"))
        assertEquals("http://h:1/ctl", UpnpDevice.resolve("http://h:1", "ctl"))
        assertEquals("http://h:1/my%20ctl", UpnpDevice.resolve("http://h:1/", " my ctl "))
    }

    @Test
    fun `a renderer embedded in another device is found, under its own name`() {
        val embedded = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>AV receiver</friendlyName><UDN>uuid:root</UDN>
            <serviceList>${service("ContentDirectory:1", "ContentDirectory", "/cd")}</serviceList>
            <deviceList><device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>AV receiver renderer</friendlyName><UDN>uuid:renderer</UDN>
              <serviceList>${service("AVTransport:2", "AVTransport", "/avt")}</serviceList>
            </device></deviceList>
          </device></root>"""
        val found = UpnpDevice.parse(embedded, location)!!
        assertEquals("AV receiver renderer", found.friendlyName)
        assertEquals("uuid:renderer", found.udn)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", found.avTransport.serviceType)
        assertNull(found.renderingControl)
        assertNull(found.connectionManager)
    }

    @Test
    fun `a device with no AVTransport is not a renderer`() {
        val server = renderer.replace("AVTransport:1", "ContentDirectory:1")
        assertNull(UpnpDevice.parse(server, location))
        assertNull(UpnpDevice.parse("<html>not upnp</html>", location))
    }

    @Test
    fun `a description a parser refuses is still read`() {
        val sloppy = renderer.replace("Living Room TV", "Tom & Jerry's <TV>")
        assertNull(UpnpXml.parse(sloppy))
        val found = UpnpDevice.parse(sloppy, location)!!
        assertEquals("Tom & Jerry's <TV>", found.friendlyName)
        assertEquals("http://192.168.1.40:49152/upnp/control/AVTransport1", found.avTransport.controlUrl)
        assertEquals("uuid:1234-abcd", found.udn)
    }

    @Test
    fun `a renderer that gives no name or UDN still gets one`() {
        val nameless = renderer.replace("<friendlyName>Living Room TV</friendlyName>", "").replace("<UDN>uuid:1234-abcd</UDN>", "")
        val found = UpnpDevice.parse(nameless, location)!!
        assertEquals("UE55", found.friendlyName)
        assertEquals("location:$location", found.udn)
    }

    // SOAP

    @Test
    fun `an action's envelope names the service and escapes its arguments once`() {
        val body = Soap.body("urn:schemas-upnp-org:service:AVTransport:1", "Seek", listOf("InstanceID" to "0", "Target" to "0:01:00", "Odd" to "a<b"))
        val root = UpnpXml.parse(body)!!
        assertEquals("Envelope", UpnpXml.localName(root))
        assertEquals("http://schemas.xmlsoap.org/soap/envelope/", root.namespaceURI)
        val action = UpnpXml.find(root, "Seek")!!
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", action.namespaceURI)
        assertEquals("0:01:00", UpnpXml.childText(action, "Target"))
        assertEquals("a<b", UpnpXml.childText(action, "Odd"))
        assertEquals(
            listOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"urn:schemas-upnp-org:service:AVTransport:1#Seek\""),
            Soap.headers("urn:schemas-upnp-org:service:AVTransport:1", "Seek"),
        )
    }

    @Test
    fun `DIDL metadata goes escaped twice, and comes out of the envelope exactly as built`() {
        val item = CastItem("id", "http://p:1/c/t/s/id.m4a?a=1&b=2", "audio/mp4", 10, 1_000, title = "Tom & Jerry")
        val didl = Didl.track(item)
        val body = Soap.body(
            "urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to item.url, "CurrentURIMetaData" to didl),
        )
        assertTrue(body.contains("&lt;DIDL-Lite"))
        assertTrue("The title's & is escaped twice", body.contains("Tom &amp;amp; Jerry"))
        assertTrue(body.contains("<CurrentURI>http://p:1/c/t/s/id.m4a?a=1&amp;b=2</CurrentURI>"))
        val action = UpnpXml.find(UpnpXml.parse(body)!!, "SetAVTransportURI")!!
        assertEquals(didl, UpnpXml.child(action, "CurrentURIMetaData")!!.textContent)
        assertEquals(item.url, UpnpXml.childText(action, "CurrentURI"))
    }

    private fun answer(action: String, inner: String) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$inner</u:${action}Response></s:Body></s:Envelope>"""

    @Test
    fun `an answer's out arguments by name`() {
        val body = answer(
            "GetPositionInfo",
            "<Track>1</Track><TrackDuration>0:03:30</TrackDuration>" +
                "<TrackMetaData>&lt;DIDL-Lite&gt;&lt;errorCode&gt;1&lt;/errorCode&gt;&lt;/DIDL-Lite&gt;</TrackMetaData>" +
                "<TrackURI>http://x/a?b=1&amp;c=2</TrackURI><RelTime>0:00:12</RelTime>",
        )
        val parsed = Soap.parse(200, body, "GetPositionInfo") as Soap.Answer.Ok
        assertEquals("0:03:30", parsed.values["TrackDuration"])
        assertEquals("0:00:12", parsed.values["RelTime"])
        assertEquals("http://x/a?b=1&c=2", parsed.values["TrackURI"])
        assertEquals("<DIDL-Lite><errorCode>1</errorCode></DIDL-Lite>", parsed.values["TrackMetaData"])
    }

    @Test
    fun `a fault gives the UPnP code and description`() {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>705</errorCode><errorDescription>Transport is locked</errorDescription></UPnPError>
            </detail></s:Fault></s:Body></s:Envelope>"""
        assertEquals(Soap.Answer.Fault(705, "Transport is locked"), Soap.parse(500, body, "SetAVTransportURI"))
        // A 200 carrying a UPnPError is still a fault.
        assertEquals(Soap.Answer.Fault(705, "Transport is locked"), Soap.parse(200, body, "SetAVTransportURI"))
    }

    @Test
    fun `answers that say little are taken for what they are`() {
        assertEquals(Soap.Answer.Ok(emptyMap()), Soap.parse(200, "", "Play"))
        assertEquals(Soap.Answer.Ok(emptyMap()), Soap.parse(200, answer("Play", ""), "Play"))
        assertEquals(Soap.Answer.Fault(null, "HTTP 500"), Soap.parse(500, "Internal error", "Play"))
        assertEquals(Soap.Answer.Fault(null, "HTTP 404"), Soap.parse(404, "", "Play"))
    }

    @Test
    fun `a sloppy answer is read by the plain search`() {
        val sloppy = answer("GetTransportInfo", "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK & fine</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>")
        assertNull(UpnpXml.parse(sloppy))
        val parsed = Soap.parse(200, sloppy, "GetTransportInfo") as Soap.Answer.Ok
        assertEquals("PLAYING", parsed.values["CurrentTransportState"])
        assertEquals("OK & fine", parsed.values["CurrentTransportStatus"])
        val fault = "<Envelope><UPnPError><errorCode>402</errorCode><errorDescription>Bad & args</errorDescription></UPnPError>"
        assertEquals(Soap.Answer.Fault(402, "Bad & args"), Soap.parse(500, fault, "Seek"))
    }

    @Test
    fun `a fault reads as an exception a person can read`() {
        assertEquals("Seek was refused (710, Seek mode not supported)", UpnpFault("Seek", 710, "Seek mode not supported").message)
        assertEquals("Play was refused", UpnpFault("Play", null, "HTTP 500").message)
    }

    // SSDP

    @Test
    fun `the search message`() {
        val text = String(Ssdp.searchMessage(), Charsets.US_ASCII)
        assertTrue(text.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(text.contains("\r\nHOST: 239.255.255.250:1900\r\n"))
        assertTrue(text.contains("\r\nMAN: \"ssdp:discover\"\r\n"))
        assertTrue(text.contains("\r\nMX: 2\r\n"))
        assertTrue(text.contains("\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    private fun reply(location: String, usn: String = "uuid:tv::urn:schemas-upnp-org:device:MediaRenderer:1", status: String = "HTTP/1.1 200 OK") =
        "$status\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nlocation: $location\r\nSERVER: Linux UPnP/1.0\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: $usn\r\n\r\n"

    @Test
    fun `a reply gives the description's url and the device's identity`() {
        val parsed = Ssdp.parseReply(reply("http://192.168.1.30:9197/dmr"))!!
        assertEquals("http://192.168.1.30:9197/dmr", parsed.location)
        assertEquals("uuid:tv", parsed.key)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", parsed.searchTarget)
        assertEquals("Linux UPnP/1.0", parsed.server)
    }

    @Test
    fun `replies that are not answers, or point off the network, are dropped`() {
        assertNull(Ssdp.parseReply(reply("http://192.168.1.30:9197/dmr", status = "NOTIFY * HTTP/1.1")))
        assertNull(Ssdp.parseReply(reply("http://192.168.1.30:9197/dmr", status = "HTTP/1.1 404 Not Found")))
        assertNull(Ssdp.parseReply("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
        assertNull(Ssdp.parseReply(reply("http://93.184.216.34/desc.xml")))
        assertNull(Ssdp.parseReply(reply("http://tv.example.com/desc.xml")))
        assertNull(Ssdp.parseReply(reply("https://192.168.1.30/desc.xml")))
        assertNull(Ssdp.parseReply(reply("file:///etc/passwd")))
        assertNull(Ssdp.parseReply("garbage"))
    }

    @Test
    fun `a device may point at its own public address, since that is where the reply came from`() {
        val from = InetAddress.getByName("93.184.216.34")
        assertNotNull(Ssdp.parseReply(reply("http://93.184.216.34/desc.xml"), from))
    }

    @Test
    fun `addresses on a home network`() {
        for (lan in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.10", "169.254.1.1", "100.64.0.1", "100.127.255.255", "127.0.0.1", "fd12:3456::1", "fe80::1")) {
            assertTrue(lan, Ssdp.isLanAddress(InetAddress.getByName(lan)))
        }
        for (wan in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888")) {
            assertFalse(wan, Ssdp.isLanAddress(InetAddress.getByName(wan)))
        }
        assertTrue(Ssdp.isLocalUrl("http://[fe80::1]:8080/d.xml"))
    }

    @Test
    fun `a search is sent more than once, and each device is kept once`() {
        val responder = DatagramSocket(0, loopback)
        val searches = mutableListOf<String>()
        val worker = thread(isDaemon = true) {
            val buffer = ByteArray(2048)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    responder.receive(packet)
                    synchronized(searches) { searches += String(packet.data, 0, packet.length, Charsets.US_ASCII) }
                    for (text in listOf(
                        reply("http://127.0.0.1:1111/a.xml", "uuid:a::urn:schemas-upnp-org:device:MediaRenderer:1"),
                        reply("http://127.0.0.1:1111/a.xml", "uuid:a::upnp:rootdevice"),
                        reply("http://127.0.0.1:2222/b.xml", "uuid:b::urn:schemas-upnp-org:device:MediaRenderer:1"),
                        reply("http://8.8.8.8/c.xml", "uuid:c::urn:schemas-upnp-org:device:MediaRenderer:1"),
                    )) {
                        val bytes = text.toByteArray(Charsets.US_ASCII)
                        responder.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    }
                }
            } catch (e: IOException) {
                // Closed.
            }
        }
        try {
            val found = Ssdp.search(timeoutMs = 800, destination = InetSocketAddress(loopback, responder.localPort))
            assertEquals(listOf("uuid:a", "uuid:b"), found.map { it.key })
            synchronized(searches) {
                assertEquals(3, searches.size)
                assertTrue(searches.all { it.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1") })
            }
        } finally {
            responder.close()
            worker.join(1_000)
        }
    }

    // HTTP to devices

    @Test
    fun `a request line, host and body`() {
        val bytes = LanHttp.requestBytes("POST", URI("http://192.168.1.5:9197/dmr/ctl?x=1"), listOf("SOAPACTION" to "\"a#b\""), "hi".toByteArray())
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("POST /dmr/ctl?x=1 HTTP/1.1\r\nHost: 192.168.1.5:9197\r\n"))
        assertTrue(text.contains("\r\nSOAPACTION: \"a#b\"\r\n"))
        assertTrue(text.contains("\r\nContent-Length: 2\r\n"))
        assertTrue(text.endsWith("Connection: close\r\n\r\nhi"))
        val get = String(LanHttp.requestBytes("GET", URI("http://h"), emptyList(), null), Charsets.ISO_8859_1)
        assertEquals("GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n", get)
        val v6 = String(LanHttp.requestBytes("GET", URI("http://[fe80::1]:80/d"), emptyList(), null), Charsets.ISO_8859_1)
        assertTrue(v6.contains("Host: [fe80::1]\r\n"))
    }

    private fun read(text: String, method: String = "GET", max: Int = LanHttp.MAX_BODY) =
        LanHttp.readResponse(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), method, max)

    @Test
    fun `answers sized by length, in chunks, or by the close`() {
        val sized = read("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: 5\r\n\r\nhelloEXTRA")
        assertEquals(200, sized.status)
        assertEquals("hello", sized.text)
        assertEquals("text/xml; charset=\"utf-8\"", sized.header("Content-Type"))

        val chunked = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=1\r\nWiki\r\n5\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\nTrailer: x\r\n\r\n")
        assertEquals("Wikipedia in\r\n\r\nchunks.", chunked.text)

        val closed = read("HTTP/1.0 500 Internal Server Error\nServer: old\n\nbody to the end")
        assertEquals(500, closed.status)
        assertEquals("body to the end", closed.text)
    }

    @Test
    fun `a 100 Continue is skipped, and a HEAD has no body`() {
        val continued = read("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        assertEquals(200, continued.status)
        assertEquals("ok", continued.text)
        val head = read("HTTP/1.1 200 OK\r\nContent-Length: 1234\r\n\r\n", method = "HEAD")
        assertEquals(0, head.body.size)
    }

    @Test
    fun `the charset the device names is used`() {
        val latin = LanHttp.readResponse(
            ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=ISO-8859-1\r\nContent-Length: 4\r\n\r\n".toByteArray() + byteArrayOf(99, 97, 102, -23)),
            "GET",
        )
        assertEquals("caf\u00E9", latin.text)
    }

    @Test
    fun `answers that are too big, cut short, or not HTTP are errors`() {
        for (bad in listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort",
            "HTTP/1.1 200 OK\r\nContent-Length: 999999999\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: ten\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n",
            "SSH-2.0-OpenSSH\r\n\r\n",
            "",
        )) {
            try {
                read(bad, max = 1000)
                fail("Took: $bad")
            } catch (e: IOException) {
                // Expected.
            }
        }
        try {
            read("HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(2000), max = 1000)
            fail("Read past the limit")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun `only http urls with a host are spoken to`() {
        for (url in listOf("https://192.168.1.2/", "ftp://h/", "http:///nohost", "not a url")) {
            try {
                LanHttp.parseUrl(url)
                fail("Took $url")
            } catch (e: IOException) {
                // Expected.
            }
        }
    }

    @Test
    fun `a whole exchange over a socket`() {
        val server = ServerSocket(0, 1, loopback)
        var request = ""
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val head = CastHttp.readHead(socket.getInputStream()) as HeadRead.Head
                val length = Regex("Content-Length: (\\d+)").find(head.text)!!.groupValues[1].toInt()
                val body = socket.getInputStream().readNBytes(length)
                request = head.text + String(body)
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nyes".toByteArray())
            }
        }
        try {
            val answer = LanHttp(answerTimeoutMs = 3_000).post("http://127.0.0.1:${server.localPort}/ctl", listOf("X" to "y"), "body".toByteArray())
            worker.join(3_000)
            assertEquals("yes", answer.text)
            assertTrue(request.startsWith("POST /ctl HTTP/1.1\r\n"))
            assertTrue(request.contains("User-Agent: ${LanHttp.USER_AGENT}\r\n"))
            assertTrue(request.endsWith("\r\n\r\nbody"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `a device that never finishes its answer is given up on`() {
        val server = ServerSocket(0, 1, loopback)
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
                    out.flush()
                    Thread.sleep(3_000)
                }
            }
        }
        val started = System.nanoTime()
        try {
            LanHttp(answerTimeoutMs = 400).get("http://127.0.0.1:${server.localPort}/slow")
            fail("Waited forever")
        } catch (e: IOException) {
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500)
        } finally {
            server.close()
            worker.interrupt()
        }
    }
}
