/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is said to yt-dlp's solver and what is read of its answer, without any solver: the shapes
 * are the ones its source gives (src/yt/solver/main.ts, read 9 Oct 2026).
 */
class SolverProtocolTest {
    @Test
    fun `a whole player is asked about with the two kinds of value, and the prepared one is asked for back`() {
        val input = Json.parseToJsonElement(SolverProtocol.input(prepared = false, signatures = listOf("SIG1", "SIG2"), ns = listOf("N1"))).jsonObject
        assertEquals("player", input.getValue("type").jsonPrimitive.content)
        assertTrue(input.getValue("output_preprocessed").jsonPrimitive.boolean)
        // The script itself is not in here: it is two megabytes, and the page takes it over the bridge.
        assertFalse("player" in input)
        val requests = input.getValue("requests").jsonArray.map { it.jsonObject }
        assertEquals(listOf("sig", "n"), requests.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(listOf("SIG1", "SIG2"), requests[0].getValue("challenges").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("N1"), requests[1].getValue("challenges").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a prepared player is asked about as one, and a kind with no values is left out`() {
        val input = Json.parseToJsonElement(SolverProtocol.input(prepared = true, signatures = emptyList(), ns = listOf("N1"))).jsonObject
        assertEquals("preprocessed", input.getValue("type").jsonPrimitive.content)
        assertFalse("output_preprocessed" in input)
        assertEquals(listOf("n"), input.getValue("requests").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
    }

    @Test
    fun `a value with a quote or a line break in it cannot break out of the question`() {
        val odd = "a\"b\\c\nd</script>"
        val input = Json.parseToJsonElement(SolverProtocol.input(prepared = true, signatures = listOf(odd), ns = emptyList())).jsonObject
        assertEquals(odd, input.getValue("requests").jsonArray[0].jsonObject.getValue("challenges").jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `the answers are read by the value they answer`() {
        val output = """{"type":"result","preprocessed_player":"PREPARED","responses":[
            {"type":"result","data":{"SIG1":"1GIS","SIG2":"2GIS"}},
            {"type":"result","data":{"N1":"1N"}}]}"""
        val reading = SolverProtocol.read(output, signatures = listOf("SIG1", "SIG2"), ns = listOf("N1")).getOrThrow()
        assertEquals(mapOf("SIG1" to "1GIS", "SIG2" to "2GIS"), reading.solved.signatures)
        assertEquals(mapOf("N1" to "1N"), reading.solved.ns)
        assertEquals("PREPARED", reading.prepared)
    }

    @Test
    fun `a kind the solver could not do is missing, and the other is still read`() {
        val output = """{"type":"result","responses":[
            {"type":"result","data":{"SIG1":"1GIS"}},
            {"type":"error","error":"Failed to extract n function"}]}"""
        val reading = SolverProtocol.read(output, signatures = listOf("SIG1"), ns = listOf("N1")).getOrThrow()
        assertEquals(mapOf("SIG1" to "1GIS"), reading.solved.signatures)
        assertEquals(emptyMap<String, String>(), reading.solved.ns)
        assertNull(reading.prepared)
        assertEquals(listOf("n: Failed to extract n function"), reading.errors)
    }

    @Test
    fun `answers come in the order of the question, so a kind left out shifts nothing`() {
        val output = """{"type":"result","responses":[{"type":"result","data":{"N1":"1N"}}]}"""
        val reading = SolverProtocol.read(output, signatures = emptyList(), ns = listOf("N1")).getOrThrow()
        assertEquals(mapOf("N1" to "1N"), reading.solved.ns)
        assertEquals(emptyMap<String, String>(), reading.solved.signatures)
    }

    @Test
    fun `an answer for a value nobody asked about, or one that is not text, is not taken`() {
        val output = """{"type":"result","responses":[{"type":"result","data":{"N1":"1N","OTHER":"x","N2":5}}]}"""
        val reading = SolverProtocol.read(output, signatures = emptyList(), ns = listOf("N1", "N2")).getOrThrow()
        assertEquals(mapOf("N1" to "1N"), reading.solved.ns)
    }

    @Test
    fun `the solver's own failure, and anything that is not an answer, is a failure with its first words`() {
        val failed = SolverProtocol.read("""{"type":"error","error":"SyntaxError: bad player\n    at parse"}""", listOf("S"), listOf("N"))
        assertEquals("the solver failed: SyntaxError: bad player", failed.exceptionOrNull()?.message)
        for (garbage in listOf("", "undefined", "[1,2]", """{"type":"result"}""", """{"type":"result","responses":[]}""")) {
            assertTrue(garbage, SolverProtocol.read(garbage, listOf("S"), listOf("N")).isFailure)
        }
    }
}
