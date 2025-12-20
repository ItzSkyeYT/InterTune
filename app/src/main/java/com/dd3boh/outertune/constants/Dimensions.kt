package com.dd3boh.outertune.constants

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.only
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets

const val CONTENT_TYPE_HEADER = 0
const val CONTENT_TYPE_LIST = 1
const val CONTENT_TYPE_SONG = 2
const val CONTENT_TYPE_ARTIST = 3
const val CONTENT_TYPE_ALBUM = 4
const val CONTENT_TYPE_PLAYLIST = 5
const val CONTENT_TYPE_FOLDER = 6

val NavigationBarHeight = 80.dp
val MiniPlayerHeight = 64.dp
val MinMiniPlayerHeight = 16.dp
val QueuePeekHeight = 48.dp
val AppBarHeight = 64.dp

val ListItemHeight = 64.dp
val SuggestionItemHeight = 56.dp
val SearchFilterHeight = 48.dp
val ListThumbnailSize = 48.dp
val GridThumbnailHeight = 96.dp
val AlbumThumbnailSize = 144.dp


val SmallCornerRadius = 8.dp
val MediumCornerRadius = 12.dp
val LargeCornerRadius = 24.dp

val ThumbnailCornerRadius = 6.dp
val AlbumCornerRadius = 16.dp
val MenuCornerRadius = 16.dp
val DialogCornerRadius = 32.dp

val PlayerHorizontalPadding = 32.dp

@OptIn(ExperimentalMaterial3Api::class)
val TopBarInsets: WindowInsets
    @Composable
    get() = TopAppBarDefaults.windowInsets
        .only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End)
        .add(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Start))

val NavigationBarAnimationSpec = spring<Dp>(stiffness = Spring.StiffnessMediumLow)
val BottomSheetAnimationSpec = spring<Dp>(stiffness = Spring.StiffnessMediumLow)
val BottomSheetSoftAnimationSpec = spring<Dp>(stiffness = Spring.StiffnessLow)
