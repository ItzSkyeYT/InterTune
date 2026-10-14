package com.zionhuang.innertube

import com.zionhuang.innertube.models.response.BrowseResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an artist's page says about the artist: the description, the subscribers and the monthly
 * audience. Offline.
 *
 * The responses in test resources/artist are Daft Punk's page (UCRr1xG_2WIDs18a6cIiCxeA), signed
 * out, saved on 14 Oct 2026 in English (en, US) and in French (fr, FR) by
 * experiments/features/artist-about/fetch_artist.py, then cut down by trim_for_test.py there: the
 * head and the About shelf as they came, one song of the other shelves.
 */
@OptIn(ExperimentalSerializationApi::class)
class ArtistAboutTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val id = "UCRr1xG_2WIDs18a6cIiCxeA"

    private fun raw(language: String): String =
        javaClass.getResource("/artist/$id-$language.json")!!.readText()

    private fun page(language: String) =
        YouTube.artistPage(id, json.decodeFromString<BrowseResponse>(raw(language)))

    /** The response with one field of the head taken out, as a page without it would come. */
    private fun pageWithout(field: String): com.zionhuang.innertube.pages.ArtistPage {
        val root = json.parseToJsonElement(raw("en")).jsonObject
        val header = root.getValue("header").jsonObject
        val head = header.getValue("musicImmersiveHeaderRenderer").jsonObject
        val trimmed = JsonObject(
            root + ("header" to JsonObject(header + ("musicImmersiveHeaderRenderer" to JsonObject(head - field))))
        )
        return YouTube.artistPage(id, json.decodeFromJsonElement(BrowseResponse.serializer(), trimmed))
    }

    @Test
    fun `the whole description is read, not only its first piece`() {
        val description = page("en").description!!
        assertTrue(description.startsWith("Daft Punk were a French electronic music duo"))
        // The first run stops at "From Wikipedia (", which was all the page kept before.
        assertTrue(description.contains("From Wikipedia (https://en.wikipedia.org/wiki/Daft_Punk)"))
        assertTrue(description.endsWith(")"))
    }

    @Test
    fun `the links keep their whole address while the text stays as YouTube shows it`() {
        val links = page("en").descriptionParts.filter { it.url != null }
        assertEquals(
            listOf(
                "https://en.wikipedia.org/wiki/Daft_Punk",
                "http://creativecommons.org/licenses/by-sa/3.0/legalcode",
            ),
            links.map { it.url }
        )
        assertEquals("http://creativecommons.org/licenses/b...", links[1].text)
    }

    @Test
    fun `subscribers and the monthly audience are read in the page's language`() {
        val english = page("en")
        assertEquals("7.19M", english.subscriberCount)
        assertEquals("74.4M monthly audience", english.monthlyListeners)

        val french = page("fr")
        assertEquals("7,19 M", french.subscriberCount)
        assertEquals("74,4 M auditeurs/mois", french.monthlyListeners)
        assertTrue(french.description!!.startsWith("Daft Punk est un groupe français"))
    }

    @Test
    fun `without a description in the head the About shelf gives it`() {
        val page = pageWithout("description")
        assertEquals(page("en").description, page.description)
    }

    @Test
    fun `a head without the audience leaves it out and keeps the rest`() {
        val page = pageWithout("monthlyListenerCount")
        assertNull(page.monthlyListeners)
        assertEquals("7.19M", page.subscriberCount)
        assertEquals("Daft Punk", page.artist.title)
    }
}
