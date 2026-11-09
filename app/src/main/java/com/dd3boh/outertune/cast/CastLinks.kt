/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.net.Network
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.Unreleased
import kotlinx.coroutines.CoroutineScope
import javax.net.SocketFactory

/** Makes the link for a receiver, whichever protocol it speaks. Only with Unreleased.CASTING on. */
object CastLinks {
    /**
     * [network] is the Wi-Fi network the receiver is on; every socket the link opens is bound to
     * it, so a VPN on the phone does not carry the traffic away from the LAN. [sound] is the app's
     * sound, which an AirPlay link puts on each packet itself (LinkCaps.shapesSound).
     */
    fun create(device: CastDevice, scope: CoroutineScope, sender: FCastLink.Sender, network: Network?, sound: PcmSound? = null): ReceiverLink {
        check(Unreleased.CASTING) { "Casting is off" }
        val sockets = network?.socketFactory ?: SocketFactory.getDefault()
        return when (device.protocol) {
            CastProtocol.FCAST -> FCastLink(device, scope, sender, sockets)
            CastProtocol.DLNA -> DlnaLink(device, scope, LanHttp(sockets))
            CastProtocol.CHROMECAST -> ChromecastLink(device, scope, sockets, userAgent = "${sender.appName}/${sender.appVersion}")
            CastProtocol.AIRPLAY -> AirPlayLink(
                device, scope, sockets,
                bindUdp = { socket -> network?.bindSocket(socket) },
                userAgent = "${sender.appName}/${sender.appVersion}",
                audit = BuildConfig.DEBUG,
                sound = sound,
            )
        }
    }
}
