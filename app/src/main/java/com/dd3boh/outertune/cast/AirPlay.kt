/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * AirPlay 1, the protocol Apple calls RAOP (Remote Audio Output Protocol): what a speaker
 * announces and the numbers both ends agree on. Read from the unofficial specification
 * (nto.github.io/AirPlay.html), openairplay's notes, and the senders in pyatv, libraop and
 * OwnTone; written here from those, not copied.
 *
 * A speaker answers `_raop._tcp` with a service named `<its MAC in hex>@<its name>` and a TXT
 * record saying what it takes. The phone then talks RTSP to it on that port, and sends the audio
 * as RTP over UDP: 352 frames a packet, 44.1 kHz, 16-bit stereo, the speaker playing each frame
 * [LATENCY_FRAMES] after the moment it is due.
 */
object AirPlay {
    const val SERVICE_TYPE = "_raop._tcp"

    /** Where classic AirPlay listens when nothing says otherwise (AirPort Express); AirPlay 2 speakers mostly use 7000. */
    const val PORT = 5000

    const val RATE = 44_100
    const val CHANNELS = 2
    const val FRAMES_PER_PACKET = 352

    /**
     * How far ahead of the speaker's playback the phone runs: two seconds, as iTunes did. The
     * speaker plays a frame this long after the sync packets say it is due, which is also how
     * long a pause, a seek or a new song takes to be heard.
     */
    const val LATENCY_FRAMES = 88_200

    /** The RTP payload type of the audio, 96, which the SDP names. */
    const val PAYLOAD_TYPE = 0x60
}

/** How the audio is packed: uncompressed Apple Lossless frames (codec 1), or bare L16 samples (codec 0). */
enum class AirPlayCodec { ALAC, PCM }

/** How the audio is sent: as it is (et 0), or AES-encrypted under a key sealed with the AirPort's RSA key (et 1). */
enum class AirPlayCrypto { CLEAR, RSA }

/**
 * What a speaker's TXT record says, the keys a sender needs: [encryptions] (et: 0 none, 1 RSA as
 * on the AirPort Express, 3 FairPlay, 4 MFi-SAP, 5 FairPlay SAP 2.5), [codecs] (cn: 0 PCM, 1 Apple
 * Lossless, 2 AAC, 3 AAC-ELD), [metadata] (md: 0 text, 1 artwork, 2 progress), [transports] (tp:
 * UDP, TCP), whether it wants a [password] (pw), its status [flags] (sf), its [model] (am), and
 * the channels, rate and sample size it plays (ch, sr, ss). A key it did not send is empty or null.
 */
data class AirPlayTxt(
    val encryptions: Set<Int> = emptySet(),
    val codecs: Set<Int> = emptySet(),
    val metadata: Set<Int> = emptySet(),
    val transports: Set<String> = emptySet(),
    val password: Boolean = false,
    val flags: Long? = null,
    val model: String? = null,
    val channels: Int? = null,
    val rate: Int? = null,
    val sampleSize: Int? = null,
) {
    /** Whether AirPlay 1 can play here without a code typed on the phone, and how. */
    fun plan(): AirPlayPlan {
        if (transports.isNotEmpty() && transports.none { it.equals("UDP", ignoreCase = true) }) {
            return AirPlayPlan.No("It takes audio over TCP only (tp=${transports.joinToString(",")})")
        }
        appleOnly(model)?.let { return AirPlayPlan.No(it) }
        val flags = flags ?: 0L
        if (password || flags and FLAG_PASSWORD != 0L) return AirPlayPlan.No("It asks for a password")
        if (flags and (FLAG_PIN or FLAG_PAIRING) != 0L) return AirPlayPlan.No("It asks for a pairing code")
        val codec = when {
            codecs.isEmpty() || 1 in codecs -> AirPlayCodec.ALAC
            0 in codecs -> AirPlayCodec.PCM
            else -> return AirPlayPlan.No("It takes only AAC (cn=${codecs.sorted().joinToString(",")})")
        }
        val crypto = when {
            encryptions.isEmpty() || 0 in encryptions -> AirPlayCrypto.CLEAR
            1 in encryptions -> AirPlayCrypto.RSA
            // Only Apple's own encryption is listed. Sent as it is all the same: some take it,
            // and the one that does not says so when it is announced.
            else -> AirPlayCrypto.CLEAR
        }
        // MFi speakers that list MFi-SAP want a POST /auth-setup before the ANNOUNCE, or refuse
        // it with 403 (OwnTone and libraop do it on et 4; some Sonos and AirPort firmware need it).
        return AirPlayPlan.Ok(codec, crypto, authSetup = 4 in encryptions)
    }

    /** The keys this was read from, as [AirPlayDevices] keeps them in a device's location. */
    fun toMap(): Map<String, String> = buildMap {
        if (encryptions.isNotEmpty()) put("et", encryptions.sorted().joinToString(","))
        if (codecs.isNotEmpty()) put("cn", codecs.sorted().joinToString(","))
        if (metadata.isNotEmpty()) put("md", metadata.sorted().joinToString(","))
        if (transports.isNotEmpty()) put("tp", transports.joinToString(","))
        if (password) put("pw", "true")
        flags?.let { put("sf", "0x" + it.toString(16)) }
        model?.let { put("am", it) }
        channels?.let { put("ch", it.toString()) }
        rate?.let { put("sr", it.toString()) }
        sampleSize?.let { put("ss", it.toString()) }
    }

    companion object {
        /** sf bit 3: a PIN must be typed. */
        const val FLAG_PIN = 0x8L

        /** sf bit 7: a password is required. */
        const val FLAG_PASSWORD = 0x80L

        /** sf bit 9: a one-time pairing is required (HomeKit). */
        const val FLAG_PAIRING = 0x200L

        /**
         * Why an Apple receiver of model [model] (am) cannot play what AirPlay 1 sends, or null for
         * any other. Read from what other senders found by 2026 (experiments/features/airplay):
         * HomePods (AudioAccessory) answer every step with 200 and play nothing since HomePod OS
         * 27; Apple TVs on tvOS (AppleTV5,3 and later) want a code, and tvOS 27 answers RAOP with
         * 403; Macs dropped AirPlay 1 in macOS 12. The old Apple TVs (2 and 3) and the AirPort
         * Express still take it.
         */
        fun appleOnly(model: String?): String? {
            val name = model?.trim() ?: return null
            if (name.startsWith("AudioAccessory", ignoreCase = true)) return "HomePods play nothing sent by AirPlay 1"
            val tv = Regex("""^AppleTV(\d+),""", RegexOption.IGNORE_CASE).find(name)
            if (tv != null && (tv.groupValues[1].toIntOrNull() ?: 0) >= 5) return "Apple TVs on tvOS take only AirPlay 2, with a code"
            if (Regex("""^(Mac|iMac|MacBook|Macmini|MacPro)\w*\d+,\d+""", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
                return "Macs take only AirPlay 2"
            }
            return null
        }

        /** A TXT record's keys and values, as text. Anything it cannot read is left out. */
        fun parse(record: Map<String, String?>): AirPlayTxt {
            val keys = record.mapKeys { it.key.lowercase() }
            fun text(key: String) = keys[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun numbers(key: String) = text(key)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()
            return AirPlayTxt(
                encryptions = numbers("et"),
                codecs = numbers("cn"),
                metadata = numbers("md"),
                transports = text("tp")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
                password = text("pw")?.equals("true", ignoreCase = true) == true,
                flags = text("sf")?.let { value ->
                    if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLongOrNull(16) else value.toLongOrNull()
                },
                model = text("am"),
                channels = text("ch")?.toIntOrNull(),
                rate = text("sr")?.toIntOrNull(),
                sampleSize = text("ss")?.toIntOrNull(),
            )
        }
    }
}

/** Whether a speaker can be played on, and in what form, or why not. */
sealed interface AirPlayPlan {
    data class Ok(val codec: AirPlayCodec, val crypto: AirPlayCrypto, val authSetup: Boolean) : AirPlayPlan

    /** It cannot be played on without something AirPlay 1 here does not do; [reason] in English, for the log. */
    data class No(val reason: String) : AirPlayPlan
}

/** AirPlay speakers as the picker lists them. */
object AirPlayDevices {
    /** What a device's location starts with when it holds an AirPlay speaker's TXT record. */
    const val LOCATION_PREFIX = "raop:"

    /**
     * A speaker as NSD found it. Its service name is `<MAC>@<name>`: the name people gave it after
     * the at sign, and the MAC, which stays the same across addresses, as its id. Its TXT record
     * rides in the device's location (`raop:et=0,4&cn=0,1`), so a speaker remembered from an
     * earlier launch (RecentReceivers) still says what it takes without being found again.
     */
    fun of(serviceName: String, host: InetAddress, port: Int, attributes: Map<String, ByteArray?>): CastDevice =
        of(serviceName, host.hostAddress ?: host.toString(), port, attributes.mapValues { it.value?.toString(Charsets.UTF_8) })

    fun of(serviceName: String, host: String, port: Int, record: Map<String, String?>): CastDevice {
        val txt = AirPlayTxt.parse(record)
        val at = serviceName.indexOf('@')
        val mac = if (at > 0) serviceName.substring(0, at) else serviceName
        val name = if (at > 0) serviceName.substring(at + 1).trim().ifEmpty { serviceName } else serviceName
        return CastDevice(
            protocol = CastProtocol.AIRPLAY,
            id = "airplay:${mac.uppercase()}",
            name = name,
            host = host,
            port = port,
            location = location(txt),
            model = modelName(txt.model),
            // An Apple TV has a screen; every other AirPlay receiver is a speaker or a box behind one.
            audioOnly = txt.model?.startsWith("AppleTV", ignoreCase = true) != true,
        )
    }

    /**
     * The model as the picker shows it: Apple's own boxes by their names ("AirPort10,115" is an
     * AirPort Express), a maker's identifier without its version digits ("Zeppelin1,2" is a
     * Zeppelin), and a name a maker wrote out as it is. Null for none.
     */
    fun modelName(model: String?): String? {
        val name = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val identifier = Regex("""^([A-Za-z][A-Za-z ]*?)\d+,\d+$""").find(name)?.groupValues?.get(1)?.trim() ?: return name
        return when {
            identifier.equals("AirPort", ignoreCase = true) -> "AirPort Express"
            identifier.equals("AppleTV", ignoreCase = true) -> "Apple TV"
            identifier.equals("AudioAccessory", ignoreCase = true) -> "HomePod"
            identifier.startsWith("Mac", ignoreCase = true) || identifier.startsWith("iMac", ignoreCase = true) -> "Mac"
            else -> identifier
        }
    }

    /** A speaker typed in by address: nothing is known of it until it answers. */
    fun typed(host: String, port: Int): CastDevice =
        CastDevice(CastProtocol.AIRPLAY, "airplay:$host:$port", host, host, port, location = location(AirPlayTxt()), audioOnly = true)

    /** The TXT record kept in [CastDevice.location]. */
    fun location(txt: AirPlayTxt): String = LOCATION_PREFIX + txt.toMap().entries.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, "UTF-8")}"
    }

    /** What [location] wrote, or an empty record for anything else. */
    fun txtOf(device: CastDevice): AirPlayTxt {
        val location = device.location?.takeIf { it.startsWith(LOCATION_PREFIX) } ?: return AirPlayTxt()
        val record = location.removePrefix(LOCATION_PREFIX).split('&').filter { '=' in it }.associate { pair ->
            pair.substringBefore('=') to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()
        }
        return AirPlayTxt.parse(record)
    }
}
