/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * 26 Nov 2026 (the whole-app scan): Android 7.0 has no ISRG Root X1, so every server with a Let's
 * Encrypt certificate failed there, ListenBrainz first, which is all the scrobbling left from
 * 1 December. The root goes beside the phone's own in every build, and plain http stays off.
 */
class TrustOnAndroid7Test {

    @Test
    fun `the root added is ISRG Root X1 itself`() {
        val pem = File("src/main/res/raw/isrg_root_x1.pem")
        val cert = pem.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        assertTrue(cert.subjectX500Principal.name, "CN=ISRG Root X1" in cert.subjectX500Principal.name)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        // The fingerprint Let's Encrypt publishes for ISRG Root X1.
        assertEquals("96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6", sha256)
    }

    @Test
    fun `every build trusts the phone's roots and that one, and no plain http`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest, "android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest)
        val anchors = "<base-config>\n        <trust-anchors>\n            <certificates src=\"system\" />\n            <certificates src=\"@raw/isrg_root_x1\" />\n        </trust-anchors>\n    </base-config>"
        val main = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(main, anchors in main)
        assertFalse("plain http stays off in a release", "cleartextTrafficPermitted=\"true\"" in main)
        // The debug build has its own file of the same name, which replaces the main one there.
        val debug = File("src/debug/res/xml/network_security_config.xml").readText()
        assertTrue(debug, anchors in debug)
    }
}
