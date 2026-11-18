package com.dd3boh.outertune.ui.screens.search

import com.dd3boh.outertune.ui.utils.dpadOverlay
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dd3boh.outertune.viewmodels.OnlineSearchViewModel
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.constants.EnabledTabsKey
import com.dd3boh.outertune.constants.PauseSearchHistoryKey
import com.dd3boh.outertune.constants.SearchSource
import com.dd3boh.outertune.constants.SearchSourceKey
import com.dd3boh.outertune.constants.UpdateAvailableKey
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.ui.component.SearchBar
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.urlEncode
import com.dd3boh.outertune.utils.YouTubeLink
import com.dd3boh.outertune.youtubeNavigator
import androidx.compose.foundation.layout.Row
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.utils.Landscape
import com.dd3boh.outertune.ui.utils.LocalLandscape
import androidx.compose.ui.unit.Dp
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget

/**
 * Whether the search pill begins with a back arrow, and not with a magnifier.
 *
 * It began with the arrow everywhere, also on a tab with nothing being searched, where there is
 * nothing to go back from and a tap on it opens search. So the arrow is for the two places it
 * leaves: search while it is open, and a page of results. [route] is the destination on screen.
 */
fun searchPillLeadsBack(searchActive: Boolean, route: String?): Boolean =
    searchActive || route?.startsWith("search") == true

/**
 * Whether the search pill is on screen: over every tab of the bar, over a page of search results,
 * and over anything while search is open. [tabs] is the bar as it is now.
 *
 * It was the bar as it was when the app was opened, so a tab that came into the bar later (Folders,
 * which turning local media on brings back, or one added under Arrange tabs) had an empty band
 * where the pill goes until the app was started again.
 */
fun searchPillShown(searchActive: Boolean, route: String?, tabs: List<Screens>): Boolean =
    searchActive || tabs.fastAny { it.route == route } || route?.startsWith("search/") == true

/**
 * Where a page keeps the words of a search left open over it when another tab was tapped (#166): in
 * its saved state, which goes away and comes back with the page as its tab does. MainActivity puts
 * them there, and [SearchBarContainer] takes them back, opening the search again, once the page is
 * on screen again.
 */
const val KEPT_SEARCH = "keptSearch"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBarContainer(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    searchActive: Boolean,
    // What is typed. Held by the caller when its tabs act on it (MainActivity, #166).
    queryState: MutableState<TextFieldValue> = rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) },
    onActiveChange: (Boolean) -> Unit,
) {
    Log.v("SearchBarContainer", "SB-1")
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val focusManager = LocalFocusManager.current
    val playerConnection = LocalPlayerConnection.current

    val enabledTabs by rememberPreference(EnabledTabsKey, defaultValue = DEFAULT_ENABLED_TABS)
    var searchSource by rememberEnumPreference(SearchSourceKey, SearchSource.ONLINE)
    val updateAvailable by rememberPreference(UpdateAvailableKey, defaultValue = false)

    // The bar as it is now: kept from when the app was opened, a tab that came into the bar later had no
    // pill over it (searchPillShown).
    val navigationItems = remember(enabledTabs) { Screens.getScreens(enabledTabs) }
    val searchBarFocusRequester = remember { FocusRequester() }
    // The app's own, the one on screen: a link that opened nothing said why on a host of its own that nothing showed.
    val snackbarHostState = LocalSnackbarHostState.current

    val (query, onQueryChange) = queryState


    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // The filter of the page of results the bar is over, read from the page's own model, which its
    // chips show: a search edited there starts on it too (SearchFilters). Null over anything else.
    val resultsFilter = navBackStackEntry
        ?.takeIf { it.destination.route == SearchFilters.ROUTE }
        ?.let { entry -> hiltViewModel<OnlineSearchViewModel>(entry).filter.collectAsState().value }

    // searchActive is hoisted to MainActivity so the navigation bar can close this overlay; see
    // the note at its declaration there.
    val onSearchActiveChange: (Boolean) -> Unit = { newActive ->
        onActiveChange(newActive)
        if (!newActive) {
            focusManager.clearFocus()
            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                onQueryChange(TextFieldValue())
            }
        }
    }

    // The words searched on YouTube Music: a link opens what it points to, anything else the page of
    // results. Also offered by the library's search when it finds nothing there, without changing
    // which search the bar opens on.
    val searchOnline: (String) -> Unit = {
        onSearchActiveChange(false)
        if (youtubeNavigator(
                context,
                navController,
                coroutineScope,
                playerConnection,
                snackbarHostState,
                it.toUri(),
                menuState,
            )
        ) {
            // don't do anything
        } else {
            navController.navigate("search/${it.urlEncode()}" + SearchFilters.routeSuffix(resultsFilter))
            if (context.dataStore[PauseSearchHistoryKey] != true) {
                database.query {
                    insert(SearchHistory(query = it))
                }
            }
        }
    }

    val onSearch: (String) -> Unit = {
        if (it.isNotEmpty()) {
            // A link opens what it links to in either search: in the library's, it searched the library for the address.
            if (searchSource == SearchSource.LOCAL && YouTubeLink.parse(it) == null) {
                focusManager.clearFocus(true)
            } else {
                searchOnline(it)
            }
        }
    }


    val shouldShowSearchBar = remember(searchActive, navBackStackEntry, navigationItems) {
        searchPillShown(searchActive, navBackStackEntry?.destination?.route, navigationItems)
    }

    // The page the bar was over when it last looked, in this composition. The search closes when the
    // page under it changes, not when the bar is composed again over the same page: a search open when
    // the app was recreated (a dark mode switch, a language or font change, the app back after Android
    // closed it in the background) stays open with its words and what they find. It closed there, and
    // the words typed over a tab went with it (18 Nov 2026).
    var lastPage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(navBackStackEntry) {
        val page = navBackStackEntry
        val moved = page != null && lastPage != null && page.id != lastPage
        if (page != null) lastPage = page.id
        if (searchActive && moved) {
            onSearchActiveChange(false)
        }
        // A results page shows its own query in the bar. Searching from a tab clears the bar on
        // the way (closing the overlay there does), and going back to earlier results left the
        // later query in it. As OnlineSearchViewModel, a null argument is the word "null". Not
        // over a search still open from before the bar was composed again: its words are the typed ones.
        if (navBackStackEntry?.destination?.route == SearchFilters.ROUTE && (moved || !searchActive)) {
            val searchQuery = navBackStackEntry?.arguments?.getString("query") ?: "null"
            if (query.text != searchQuery) {
                onQueryChange(TextFieldValue(searchQuery, TextRange(searchQuery.length)))
            }
        }
        // A search left open on this page when another tab was tapped comes back open, with its words
        // and what they find, now the page is back (#166, KEPT_SEARCH). Only where the bar is: the pages
        // a search can be left open on.
        if (page != null && page.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) &&
            (navigationItems.fastAny { it.route == page.destination.route } || page.destination.route == SearchFilters.ROUTE)
        ) {
            val kept = page.savedStateHandle.remove<String>(KEPT_SEARCH)
            if (!kept.isNullOrEmpty()) {
                onQueryChange(TextFieldValue(kept, TextRange(kept.length)))
                onSearchActiveChange(true)
            }
        }
    }

    AnimatedVisibility(
        visible = shouldShowSearchBar,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        SearchBar(
            // The pill, which grows to the whole screen while searching, floats over the page.
            pillModifier = Modifier.tourTarget(Tour.SEARCH_BAR).dpadOverlay(),
            closedMaxWidth = if (LocalLandscape.current.active) Landscape.PanelMaxWidth else Dp.Unspecified,
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            active = searchActive,
            onActiveChange = onSearchActiveChange,
            scrollBehavior = scrollBehavior,
            placeholder = {
                Text(
                    text = stringResource(
                        if (!searchActive) R.string.search
                        else when (searchSource) {
                            SearchSource.LOCAL -> R.string.search_library
                            SearchSource.ONLINE -> R.string.search_yt_music
                        }
                    ),
                    // The field is one line high. A hint too long for it used to wrap, and its
                    // second line was cut through by the field's lower edge.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                IconButton(
                    onClick = {
                        when {
                            searchActive -> onSearchActiveChange(false)

                            !searchActive && navBackStackEntry?.destination?.route?.startsWith(
                                "search"
                            ) == true -> {
                                navController.navigateUp()
                            }

                            else -> onSearchActiveChange(true)
                        }
                    },
                ) {
                    // The same button in the same place either way. Only its picture says
                    // which of the three things above a tap will do, and its name for a screen
                    // reader, which it did not have: the first stop on every tab said nothing.
                    val leadsBack = searchPillLeadsBack(searchActive, navBackStackEntry?.destination?.route)
                    Icon(
                        imageVector = if (leadsBack) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Search,
                        contentDescription = stringResource(if (leadsBack) R.string.back else R.string.search)
                    )
                }
            },
            trailingIcon = {
                if (searchActive) {
                    if (query.text.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange(TextFieldValue("")) }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            searchSource =
                                if (searchSource == SearchSource.ONLINE) SearchSource.LOCAL else SearchSource.ONLINE
                        }
                    ) {
                        Icon(
                            imageVector = when (searchSource) {
                                SearchSource.LOCAL -> Icons.Rounded.LibraryMusic
                                SearchSource.ONLINE -> Icons.Rounded.Language
                            },
                            contentDescription = null
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Beside settings rather than inside a menu. Identifying what is playing
                        // in the room is something people reach for while the song is still on,
                        // and a thing you have to go and find is a thing you find too late.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .tourTarget(Tour.RECOGNISE)
                                .clickable { navController.navigate("recognition") }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = stringResource(R.string.recognise)
                            )
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .tourTarget(Tour.SETTINGS)
                                .clickable {
                                    navController.navigate("settings")
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                    }
                }
            },
            windowInsets = WindowInsets.safeDrawing.union(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Start)),
            focusRequester = searchBarFocusRequester,
        ) {
            Log.v("SearchBarContainer", "SB-2")
            Crossfade(
                targetState = searchSource,
                label = "",
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) { searchSource ->
                when (searchSource) {
                    SearchSource.LOCAL -> LocalSearchScreen(
                        query = query.text,
                        navController = navController,
                        onDismiss = { onSearchActiveChange(false) },
                        onSearchOnline = searchOnline,
                    )

                    SearchSource.ONLINE -> OnlineSearchScreen(
                        query = query.text,
                        onQueryChange = onQueryChange,
                        navController = navController,
                        onSearch = {
                            if (youtubeNavigator(
                                    context,
                                    navController,
                                    coroutineScope,
                                    playerConnection,
                                    snackbarHostState,
                                    it.toUri(),
                                    menuState,
                                )
                            ) {
                                return@OnlineSearchScreen
                            } else {
                                navController.navigate("search/${it.urlEncode()}" + SearchFilters.routeSuffix(resultsFilter))
                                if (context.dataStore[PauseSearchHistoryKey] != true) {
                                    database.query {
                                        insert(SearchHistory(query = it))
                                    }
                                }
                            }
                        },
                        onDismiss = { onSearchActiveChange(false) },
                    )
                }
            }
        }
    }
}
