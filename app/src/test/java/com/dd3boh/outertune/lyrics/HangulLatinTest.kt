/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Korean in Latin letters as it is said ([HangulLatin]). The words are everyday ones, and each
 * line is invented. What ICU's own Hangul-Latin made of the same words on an Android 17 emulator
 * is in the comments: experiments/features/lyrics-latin/probe.
 */
class HangulLatinTest {

    private fun latin(korean: String) = HangulLatin.of(korean)

    @Test
    fun `a syllable's last consonant carries over to a vowel after it`() {
        assertEquals("saranghae", latin("사랑해")) // ICU: salanghae
        assertEquals("georeoyo", latin("걸어요"))
        assertEquals("hangugeo", latin("한국어"))
        assertEquals("masisseo", latin("맛있어"))
        assertEquals("kkochi", latin("꽃이"))
        assertEquals("bakke", latin("밖에"))
        assertEquals("eumak", latin("음악"))
        assertEquals("yeongwonhi", latin("영원히"))
    }

    @Test
    fun `two consonants at the end split over the vowel, and a silent h stays silent`() {
        assertEquals("gwaenchanayo", latin("괜찮아요")) // ICU: gwaenchanh-ayo
        assertEquals("eopseo", latin("없어")) // ICU: eobs-eo
        assertEquals("eulpeo", latin("읊어"))
        assertEquals("joa", latin("좋아")) // ICU: joh-a
        assertEquals("sireo", latin("싫어"))
        assertEquals("ilgo", latin("읽고")) // ICU: ilg-go
        assertEquals("dak", latin("닭")) // ICU: dalg
    }

    @Test
    fun `d and t before i are said j and ch`() {
        assertEquals("gachi", latin("같이")) // ICU: gat-i
        assertEquals("guji", latin("굳이"))
        assertEquals("dachida", latin("닫히다"))
    }

    @Test
    fun `nasals, l and h change what is next to them`() {
        assertEquals("gamsahamnida", latin("감사합니다")) // ICU: gamsahabnida
        assertEquals("binnaneun", latin("빛나는")) // ICU: bichnaneun
        assertEquals("hangungmal", latin("한국말"))
        assertEquals("dongnip", latin("독립")) // ICU: doglib
        assertEquals("inneun", latin("있는"))
        assertEquals("simni", latin("심리"))
        assertEquals("jongno", latin("종로"))
        assertEquals("molla", latin("몰라"))
        assertEquals("silla", latin("신라")) // ICU: sinla
        assertEquals("seollal", latin("설날"))
        assertEquals("manta", latin("많다")) // ICU: manhda
        assertEquals("joko", latin("좋고"))
        assertEquals("nonneun", latin("놓는")) // ICU: nohneun
        assertEquals("chuka", latin("축하"))
        assertEquals("ipak", latin("입학"))
    }

    @Test
    fun `the sound rules stop at a space, and what is not hangul is left alone`() {
        assertEquals("achim haetsari changmune daa", latin("아침 햇살이 창문에 닿아"))
        assertEquals("oneul bam byeolbit araeseo", latin("오늘 밤 별빛 아래서"))
        assertEquals("ilgo sipeun chaegi mana", latin("읽고 싶은 책이 많아"))
        assertEquals("Baby saranghae, uri gachi", latin("Baby 사랑해, 우리 같이"))
        assertEquals("sarang 愛", latin("사랑 愛"))
        assertEquals("kkk yuyu", latin("ㅋㅋㅋ ㅠㅠ"))
        assertEquals("", latin(""))
    }
}
