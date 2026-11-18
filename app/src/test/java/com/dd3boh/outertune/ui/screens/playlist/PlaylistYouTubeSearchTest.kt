/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import com.dd3boh.outertune.constants.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A playlist's search on YouTube Music (Unreleased.PLAYLIST_YT_SEARCH), from the picture he picked
 * (experiments/mocks-1115/2-playlist-search, A): the switch at the end of the pill as the library search has it,
 * YouTube Music's songs in the playlist's place with the add button and the three dots, and what it says while it
 * waits, when nothing is found and when YouTube does not answer.
 */
class PlaylistYouTubeSearchTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()

    @Test
    fun `the switch is there on a playlist that can be changed, never in the search of an empty one`() {
        assertTrue(PlaylistSearch.offered(flag = true, editable = true, addMode = false))
        assertFalse(PlaylistSearch.offered(flag = false, editable = true, addMode = false))
        // Someone else's playlist, or one synced in Read only: a song found could not go in.
        assertFalse(PlaylistSearch.offered(flag = true, editable = false, addMode = false))
        // An empty playlist's search looks on YouTube Music already.
        assertFalse(PlaylistSearch.offered(flag = true, editable = true, addMode = true))
    }

    @Test
    fun `the search looks on YouTube Music only with the switch there and set so`() {
        assertTrue(PlaylistSearch.online(offered = true, source = SearchSource.ONLINE))
        assertFalse(PlaylistSearch.online(offered = true, source = SearchSource.LOCAL))
        // A choice kept from another playlist, on one where the switch is not offered.
        assertFalse(PlaylistSearch.online(offered = false, source = SearchSource.ONLINE))
    }

    @Test
    fun `a song shows the tick when it is in the playlist already or was added from the search`() {
        assertTrue(PlaylistSearch.inPlaylist("a", playlistIds = setOf("a", "b"), justAdded = emptySet()))
        assertTrue(PlaylistSearch.inPlaylist("c", playlistIds = setOf("a", "b"), justAdded = setOf("c")))
        assertFalse(PlaylistSearch.inPlaylist("d", playlistIds = setOf("a", "b"), justAdded = setOf("c")))
    }

    @Test
    fun `the wheel turns from the first letter until the answer for the words in the field`() {
        assertTrue(PlaylistSearch.waiting(query = "daft", answered = "", searching = false))
        assertTrue(PlaylistSearch.waiting(query = "daft punk", answered = "daft", searching = false))
        assertTrue(PlaylistSearch.waiting(query = "daft punk", answered = "daft punk", searching = true))
        assertFalse(PlaylistSearch.waiting(query = "daft punk", answered = "daft punk", searching = false))
        // An empty field waits for nothing.
        assertFalse(PlaylistSearch.waiting(query = "", answered = "daft", searching = false))
        assertFalse(PlaylistSearch.waiting(query = "  ", answered = "", searching = false))
    }

    @Test
    fun `the pill has the switch at its end, as the library search has it, and only the playlist's pill`() {
        val bar = code("ui/component/FloatingTopBar.kt").substringAfter("fun TopBarSearchField(").substringBefore("fun TopBarPill(")
        assertTrue("trailing: (@Composable () -> Unit)? = null," in bar)
        // Without it the field is laid out as it always was.
        assertTrue("field(modifier.fillMaxWidth().padding(horizontal = 18.dp))" in bar)
        assertTrue("field(modifier.weight(1f).padding(start = 18.dp, end = 4.dp))\n                trailing()" in bar)
        for (other in listOf("ui/screens/playlist/AutoPlaylistScreen.kt", "ui/screens/playlist/OnlinePlaylistScreen.kt")) {
            assertFalse(other, "trailing =" in code(other).substringAfter("TopBarSearchField(").substringBefore(")\n"))
        }
        val screen = code("ui/screens/playlist/LocalPlaylistScreen.kt")
        assertTrue("trailing = if (searchSwitch) (@Composable {" in screen && "}) else null," in screen)
        assertTrue("placeholder = stringResource(if (searchOnline) R.string.search_yt_music else R.string.search)," in screen)
        // Kept as the library search keeps its own, the playlist first.
        assertTrue("var searchSource by rememberEnumPreference(PlaylistSearchSourceKey, SearchSource.LOCAL)" in screen)
        assertTrue("val PlaylistSearchSourceKey = stringPreferencesKey(\"playlistSearchSource\")" in code("constants/PreferenceKeys.kt"))
        // The library search's pictures: the globe for YouTube Music; the playlist's queue for the playlist.
        val search = code("ui/screens/search/SearchScreen.kt")
        assertTrue("SearchSource.ONLINE -> Icons.Rounded.Language" in search)
        val switch = code("ui/screens/playlist/PlaylistYouTubeSearch.kt").substringAfter("fun PlaylistSearchSwitch(").substringBefore("fun LazyListScope.")
        assertTrue("imageVector = if (online) Icons.Rounded.Language else Icons.AutoMirrored.Rounded.QueueMusic," in switch)
        assertTrue(".toggleable(value = online, role = Role.Switch, onValueChange = onChange)" in switch)
        assertTrue("stringResource(R.string.search_yt_music)" in switch && ".semantics { contentDescription = name }" in switch)
    }

    @Test
    fun `on YouTube Music its songs take the playlist's place, each with the add button and its three dots`() {
        val screen = code("ui/screens/playlist/LocalPlaylistScreen.kt")
        val online = screen.substringAfter("if (searchOnline) {\n                val playlistIds").substringBefore("// songs")
        assertTrue(online, "return@listContent" in online)
        assertTrue(online, "results = addResults," in online)
        assertTrue(online, "onAdd = { song -> playlistWithSongs.first?.let { viewModel.addSong(it, song) } }," in online)
        assertTrue(online, "YouTubeSongMenu(" in online)
        assertTrue(online, "YouTubeQueue.radio(song.toMediaMetadata())" in online && "origin = PlayOrigin.SEARCH," in online)
        // Not the playlist's order and lock over them.
        assertTrue("if (!searchOnline) item(\n                        key = \"action header\"," in screen)
        // The pill's words are the search's; the empty playlist's own field keeps its words.
        assertTrue("if (searchOnline) viewModel.addQuery.value = query.text\n        else if (!addMode) viewModel.addQuery.value = \"\"" in screen)

        val rows = code("ui/screens/playlist/PlaylistYouTubeSearch.kt").substringAfter("fun LazyListScope.playlistYouTubeResults(")
        val trailing = rows.substringAfter("trailingContent = {").substringBefore("modifier = Modifier")
        assertTrue(trailing, "enabled = !added," in trailing)
        assertTrue(trailing, "imageVector = if (added) Icons.Rounded.Check else Icons.Rounded.Add," in trailing)
        assertTrue(trailing, "contentDescription = stringResource(R.string.add_to_playlist)," in trailing)
        assertTrue(trailing, "Icons.Rounded.MoreVert," in trailing)
        assertTrue(trailing.indexOf("Icons.Rounded.Add") < trailing.indexOf("Icons.Rounded.MoreVert"))
        assertTrue(rows, "items(items = results, key = { \"yt_\" + it.id })" in rows)
        assertTrue(rows, "onLongClick = { onMenu(song) }," in rows)
    }

    @Test
    fun `no selection while YouTube Music's songs are listed, so its bar never acts on the playlist's hidden rows`() {
        // Found 18 Nov 2026: a song picked, then the search opened on YouTube Music (the remembered choice), and the
        // bar stayed up over YouTube's songs: Select all took every row of the playlist, and Remove from playlist
        // emptied it, on YouTube Music too when synced.
        val screen = code("ui/screens/playlist/LocalPlaylistScreen.kt")
        // Opening the search there ends it, and so does a screen brought back after Android closed the app: both the
        // selection and the open search are saved with the screen.
        assertTrue("LaunchedEffect(searchOnline, inSelectMode) {\n        if (searchOnline && inSelectMode) onExitSelectionMode()\n    }" in screen)
        assertTrue("var inSelectMode by rememberSaveable { mutableStateOf(false) }" in screen)
        assertTrue("var isSearching by rememberSaveable { mutableStateOf(false) }" in screen)
        // The bar is not drawn for the frame before the effect runs, and there is no other bar.
        assertTrue("FloatingFooter(inSelectMode && !searchOnline) {" in screen)
        assertEquals(1, Regex("""FloatingFooter\(""").findAll(screen).count())
        // Nor can its Select all reach a row while it slides away over them.
        assertTrue("val shownSongs = if (searchOnline) emptyList<PlaylistSong>() else if (isSearching) mutableSongs else playlistWithSongs.second" in screen)
        // The switch's own tap ends it, as it did.
        val switch = screen.substringAfter("PlaylistSearchSwitch(online = ").substringBefore("}) else null,")
        assertTrue(switch, "onExitSelectionMode()" in switch)
        // And YouTube Music's rows have no way into it: a long press opens the song's menu.
        val params = code("ui/screens/playlist/PlaylistYouTubeSearch.kt")
            .substringAfter("fun LazyListScope.playlistYouTubeResults(").substringBefore(") {")
        assertTrue(params, listOf("inSelectMode", "onSelect", "selection").none { it in params })
    }

    @Test
    fun `a search YouTube did not answer says why with the fix, and one with nothing found says so`() {
        val rows = code("ui/screens/playlist/PlaylistYouTubeSearch.kt").substringAfter("fun LazyListScope.playlistYouTubeResults(")
        val after = rows.substringAfter("if (!waiting && results.isEmpty() && query.isNotBlank()) {")
        assertTrue(after, "FailedNote(kind = failed, onSignIn = onSignIn, onRetry = onRetry)" in after)
        assertTrue(after, "text = stringResource(R.string.playlist_empty_no_results)," in after)
        val model = code("viewmodels/LocalPlaylistViewModel.kt")
        assertTrue("_addFailed.value = answer.exceptionOrNull()?.let { WhyStopped.kindOf(it) }" in model)
        assertTrue("combine(addQuery, addRetries) { query, _ -> query }.debounce(350).collectLatest { query ->" in model)
        assertTrue("_addAnswered.value = query\n                _addSearching.value = false" in model)
        assertTrue("onRetry = viewModel::retryAdd," in code("ui/screens/playlist/LocalPlaylistScreen.kt"))
    }

    @Test
    fun `the flag is held back and there is no new text`() {
        assertTrue("val PLAYLIST_YT_SEARCH = BuildConfig.PREVIEW_FEATURES" in code("constants/Unreleased.kt"))
        val english = File("src/main/res/values/strings.xml").readText()
        assertTrue("<string name=\"search_yt_music\">Search YouTube Music…</string>" in english)
        // Read once, in code: comments that name it aside.
        val screen = code("ui/screens/playlist/LocalPlaylistScreen.kt").lines().joinToString("\n") { it.substringBefore("//") }
        assertEquals(1, Regex("""Unreleased\.PLAYLIST_YT_SEARCH\b""").findAll(screen).count())
    }
}
