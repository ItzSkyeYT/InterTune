package com.zionhuang.innertube

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.concurrent.thread

class IpFamilyTest {
    private val v4a = InetAddress.getByName("192.0.2.1")
    private val v4b = InetAddress.getByName("192.0.2.2")
    private val v6a = InetAddress.getByName("2001:db8::1")
    private val v6b = InetAddress.getByName("2001:db8::2")

    /** The order a resolver usually answers in on a dual-stack line: IPv6 first. */
    private val dualStack = listOf(v6a, v4a, v6b, v4b)

    @Test
    fun `IPv4 goes first and the families take turns`() {
        assertEquals(listOf(v4a, v6a, v4b, v6b), AddressPolicy(IpFamily.V4).order(dualStack))
    }

    @Test
    fun `IPv6 first keeps IPv4 as the fallback`() {
        assertEquals(listOf(v6a, v4a, v6b, v4b), AddressPolicy(IpFamily.V6).order(dualStack))
    }

    @Test
    fun `the family with more addresses ends the list`() {
        val manyV4 = (1..4).map { InetAddress.getByName("192.0.2.$it") }
        assertEquals(
            listOf(manyV4[0], v6a, manyV4[1], manyV4[2], manyV4[3]),
            AddressPolicy(IpFamily.V4).order(manyV4 + v6a),
        )
    }

    @Test
    fun `an IPv6-only network still connects when IPv4 is preferred`() {
        // No A record at all, as on NAT64: the preference has nothing to reorder.
        assertEquals(listOf(v6a, v6b), AddressPolicy(IpFamily.V4).order(listOf(v6a, v6b)))
    }

    @Test
    fun `only leaves the other family out`() {
        assertEquals(listOf(v6a, v6b), AddressPolicy(IpFamily.V6, only = true).order(dualStack))
        assertEquals(listOf(v4a, v4b), AddressPolicy(IpFamily.V4, only = true).order(dualStack))
    }

    @Test(expected = UnknownHostException::class)
    fun `asking for a family the network lacks fails before any request is made`() {
        FamilyDns(AddressPolicy(IpFamily.V6, only = true)) { listOf(v4a) }.lookup("music.youtube.com")
    }

    @Test
    fun `the other family of each is the other one`() {
        assertEquals(IpFamily.V6, IpFamily.V4.other)
        assertEquals(IpFamily.V4, IpFamily.V6.other)
        assertEquals(IpFamily.V6, IpFamily.of(v6a))
        assertEquals(IpFamily.V4, IpFamily.of(v4a))
    }

    /**
     * The whole point, end to end on loopback: a name that resolves IPv6 first, a server on each
     * family, and a client told to prefer IPv4 must reach the IPv4 one.
     *
     * OkHttp's default fast fallback reorders the addresses to put IPv6 first and then races them,
     * so a Dns that orders IPv4 first is not enough on its own and this fails without
     * fastFallback(false).
     */
    @Test
    fun `preferring IPv4 is not undone by OkHttp's connection race`() {
        val v6Server = runCatching { ServerSocket().apply { bind(InetSocketAddress("::1", 0)) } }.getOrNull()
        assumeTrue("no IPv6 loopback here", v6Server != null)
        val port = v6Server!!.localPort
        val v4Server = runCatching { ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", port)) } }.getOrNull()
        assumeTrue("port $port taken on IPv4 loopback", v4Server != null)
        try {
            serve(v6Server, "v6")
            serve(v4Server!!, "v4")
            val loopbacks = Dns { listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")) }

            fun ask(policy: AddressPolicy): String {
                val client = OkHttpClient.Builder()
                    .apply(keepToFamily(policy, loopbacks))
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                return client.newCall(Request.Builder().url("http://dual.test:$port/").build()).execute()
                    .use { it.body.string() }
            }

            assertEquals("v4", ask(AddressPolicy(IpFamily.V4)))
            assertEquals("v6", ask(AddressPolicy(IpFamily.V6)))
        } finally {
            v6Server.close()
            v4Server?.close()
        }
    }

    /**
     * A line whose IPv4 drops every packet, with as many IPv4 addresses as music.youtube.com has:
     * preferring IPv4 must still reach IPv6 well inside the request's time. Tried in order, all
     * the IPv4 ones first, the call ran out of time after two of them.
     */
    @Test
    fun `a family that drops every packet does not use up the request's time`() {
        val v6Server = runCatching { ServerSocket().apply { bind(InetSocketAddress("::1", 0)) } }.getOrNull()
        assumeTrue("no IPv6 loopback here", v6Server != null)
        try {
            serve(v6Server!!, "v6")
            val deadV4 = (1..13).map { InetAddress.getByName("192.0.2.$it") }
            val dns = Dns { deadV4 + InetAddress.getByName("::1") }
            val client = OkHttpClient.Builder()
                .apply(keepToFamily(AddressPolicy(IpFamily.V4), dns))
                .socketFactory(DropsIpv4())
                .connectTimeout(1, TimeUnit.SECONDS)
                .callTimeout(2500, TimeUnit.MILLISECONDS)
                .build()
            val body = client.newCall(Request.Builder().url("http://dual.test:${v6Server.localPort}/").build())
                .execute().use { it.body.string() }
            assertEquals("v6", body)
        } finally {
            v6Server?.close()
        }
    }

    /** Sockets whose IPv4 connections are never answered: each waits out its timeout. */
    private class DropsIpv4 : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                if ((endpoint as? InetSocketAddress)?.address is Inet4Address) {
                    Thread.sleep(timeout.toLong())
                    throw SocketTimeoutException("connect timed out")
                }
                super.connect(endpoint, timeout)
            }
        }

        override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    /** Answers every connection with [body] until the socket is closed. */
    private fun serve(server: ServerSocket, body: String) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()
                        )
                        it.getOutputStream().flush()
                    }
                }
            }
        }
    }
}
