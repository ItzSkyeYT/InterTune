package com.dd3boh.outertune.ui.screens.search

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.constants.EnabledTabsKey
import com.dd3boh.outertune.extensions.tabMode
import com.dd3boh.outertune.ui.component.SearchBar
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBarContainer(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    windowInsets: WindowInsets = WindowInsets.safeDrawing.union(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)
    ),
) {
    Log.v("SearchBarContainer", "SB-1")
    val focusManager = LocalFocusManager.current

    val enabledTabs by rememberPreference(EnabledTabsKey, defaultValue = DEFAULT_ENABLED_TABS)

    val navigationItems = remember { Screens.getScreens(enabledTabs) }
    val searchBarFocusRequester = remember { FocusRequester() }

    val (query, onQueryChange) = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    var searchActive by rememberSaveable {
        mutableStateOf(false)
    }
    val onSearchActiveChange: (Boolean) -> Unit = { newActive ->
        searchActive = newActive
        if (!newActive) {
            focusManager.clearFocus()
            if (navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route }) {
                onQueryChange(TextFieldValue())
            }
        }
    }

    val onSearch: (String) -> Unit = {
        if (it.isNotEmpty()) {
            focusManager.clearFocus(true)
        }
    }


    val shouldShowSearchBar = remember(searchActive, navBackStackEntry) {
        (searchActive || navigationItems.fastAny { it.route == navBackStackEntry?.destination?.route } ||
                navBackStackEntry?.destination?.route?.startsWith("search/") == true)
    }

    LaunchedEffect(navBackStackEntry) {
        if (searchActive) {
            onSearchActiveChange(false)
        }
    }

    AnimatedVisibility(
        visible = shouldShowSearchBar,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            active = searchActive,
            onActiveChange = onSearchActiveChange,
            scrollBehavior = scrollBehavior,
            placeholder = {
                Text(text = stringResource(if (!searchActive) R.string.search else R.string.search_library))
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
                    Icon(
                        imageVector =
                            if (searchActive || navBackStackEntry?.destination?.route?.startsWith("search") == true) {
                                Icons.AutoMirrored.Rounded.ArrowBack
                            } else {
                                Icons.Rounded.Search
                            },
                        contentDescription = null
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
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable {
                                navController.navigate("settings")
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null
                        )
                    }
                }
            },
            windowInsets = windowInsets,
            focusRequester = searchBarFocusRequester,
        ) {
            Log.v("SearchBarContainer", "SB-2")
            LocalSearchScreen(
                query = query.text,
                navController = navController,
                onDismiss = { onSearchActiveChange(false) },
            )
        }
    }
}
