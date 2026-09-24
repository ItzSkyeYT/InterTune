package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.MaxImageCacheSizeKey
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.extensions.tryOrNull
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.utils.formatFileSize
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ColumnScope.SongCacheFrag() {
    val coroutineScope = rememberCoroutineScope()
    val playerCache = LocalPlayerConnection.current?.service?.playerCache ?: return

    val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(key = MaxSongCacheSizeKey, defaultValue = 0)

    var playerCacheSize by remember {
        mutableLongStateOf(tryOrNull { playerCache.cacheSpace } ?: 0)
    }

    LaunchedEffect(playerCache) {
        while (isActive) {
            delay(2000)
            playerCacheSize = tryOrNull { playerCache.cacheSpace } ?: 0
        }
    }

    var showClearConfirmDialog by remember {
        mutableStateOf(false)
    }

    Spacer(modifier = Modifier.height(16.dp))
    if (maxSongCacheSize != 0) {
        if (maxSongCacheSize == -1) {
            Text(
                text = stringResource(R.string.size_used, formatFileSize(playerCacheSize)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else {
            LinearProgressIndicator(
                progress = { (playerCacheSize.toFloat() / (maxSongCacheSize * 1024 * 1024L)).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Text(
                text = stringResource(
                    R.string.size_used,
                    "${formatFileSize(playerCacheSize)} / ${formatFileSize(maxSongCacheSize * 1024 * 1024L)}"
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }

    ListPreference(
        title = { Text(stringResource(R.string.song_cache_max_size)) },
        selectedValue = maxSongCacheSize,
        values = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1),
        valueText = {
            when (it) {
                0 -> stringResource(androidx.compose.ui.R.string.state_off)
                -1 -> stringResource(R.string.unlimited)
                else -> formatFileSize(it * 1024 * 1024L)
            }
        },
        onValueSelected = onMaxSongCacheSizeChange
    )
    InfoLabel(stringResource(R.string.restart_to_apply_changes))

    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_song_cache)) },
        onClick = {
            showClearConfirmDialog = true
        },
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showClearConfirmDialog) {
        DefaultDialog(
            onDismiss = { showClearConfirmDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_song_cache_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            playerCache.keys.forEach { key ->
                                playerCache.removeResource(key)
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalCoilApi::class)
@Composable
fun ColumnScope.ImageCacheFrag() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val imageDiskCache = context.imageLoader.diskCache ?: return

    val (maxImageCacheSize, onMaxImageCacheSizeChange) = rememberPreference(
        key = MaxImageCacheSizeKey,
        defaultValue = 512
    )

    var imageCacheSize by remember {
        mutableLongStateOf(imageDiskCache.size)
    }

    LaunchedEffect(imageDiskCache) {
        while (isActive) {
            delay(500)
            imageCacheSize = imageDiskCache.size
        }
    }

    // clear caches when turning off
    LaunchedEffect(maxImageCacheSize) {
        if (maxImageCacheSize == 0) {
            coroutineScope.launch(Dispatchers.IO) {
                imageDiskCache.clear()
            }
        }
    }

    var showClearConfirmDialog by remember {
        mutableStateOf(false)
    }

    if (maxImageCacheSize > 0) {
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { (imageCacheSize.toFloat() / imageDiskCache.maxSize).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Text(
            text = stringResource(
                R.string.size_used,
                "${formatFileSize(imageCacheSize)} / ${formatFileSize(imageDiskCache.maxSize)}"
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }

    ListPreference(
        title = { Text(stringResource(R.string.image_cache_max_size)) },
        selectedValue = maxImageCacheSize,
        values = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192),
        valueText = {
            when (it) {
                0 -> stringResource(androidx.compose.ui.R.string.state_off)
                else -> formatFileSize(it * 1024 * 1024L)
            }
        },
        onValueSelected = onMaxImageCacheSizeChange
    )
    InfoLabel(stringResource(R.string.restart_to_apply_changes))

    PreferenceEntry(
        title = { Text(stringResource(R.string.clear_image_cache)) },
        onClick = {
            showClearConfirmDialog = true
        },
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showClearConfirmDialog) {
        DefaultDialog(
            onDismiss = { showClearConfirmDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_image_cache_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            imageDiskCache.clear()
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}
