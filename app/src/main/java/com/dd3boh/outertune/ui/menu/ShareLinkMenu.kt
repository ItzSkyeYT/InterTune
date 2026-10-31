/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.utils.BuiltInKeys
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ShareLinkKindKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.utils.ShareAction
import com.dd3boh.outertune.utils.ShareLink
import com.dd3boh.outertune.utils.ShareLinkKind
import com.dd3boh.outertune.utils.ShareLinks
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.shareSheet

/**
 * Shares a song or an album the way "Links you share" says. Call the result from a click, with
 * what closes the menu the click came from: it is run once it is known what goes out, so that the
 * menu is still there, under the question, while nothing is chosen yet.
 *
 * Nothing is chosen at first (ShareLinks.START). The first share that has a page asks which link to
 * send, and the pick is the setting from then on: every share after it goes straight through,
 * and the row in Settings shows the pick and changes it. Backing out of the question shares
 * nothing and keeps nothing, so the next share asks again.
 */
@Composable
fun rememberShareLink(): (link: ShareLink, done: () -> Unit) -> Unit {
    val context = LocalContext.current
    var chosen by rememberEnumPreference(ShareLinkKindKey, defaultValue = ShareLinks.START)
    var asking by remember { mutableStateOf<Pair<ShareLink, () -> Unit>?>(null) }

    asking?.let { (link, done) ->
        ShareLinkChoice(link, onDismiss = { asking = null }) { kind ->
            asking = null
            // Kept before the menu closes. The write does not depend on the menu staying open
            // (rememberEnumPreference writes outside the screen's own scope).
            chosen = kind
            done()
            context.shareText(ShareLinks.text(link, kind))
        }
    }

    return { link, done ->
        when (val action = ShareLinks.decide(link, if (Unreleased.SHARE_PAGE && BuiltInKeys.official) chosen else ShareLinkKind.YOUTUBE_MUSIC)) {
            is ShareAction.Send -> {
                done()
                context.shareText(action.text)
            }

            ShareAction.Ask -> asking = link to done
        }
    }
}

private fun Context.shareText(text: String) {
    val intent = Intent().apply {
        action = Intent.ACTION_SEND
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(shareSheet(intent))
}

/** The question, asked once: the two links, then a line on where the pick can be changed. */
@Composable
private fun ShareLinkChoice(link: ShareLink, onDismiss: () -> Unit, onPick: (ShareLinkKind) -> Unit) {
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.share_link_choice_title)) },
    ) {
        ShareLinkRow(Icons.Rounded.MusicNote, R.string.share_link_youtube_music, R.string.share_link_youtube_music_description) {
            onPick(ShareLinkKind.YOUTUBE_MUSIC)
        }
        if (link.page != null) {
            ShareLinkRow(Icons.Rounded.Public, R.string.share_link_page, R.string.share_link_page_description) {
                onPick(ShareLinkKind.PAGE)
            }
        }
        Text(
            text = stringResource(R.string.share_link_choice_kept, stringResource(R.string.grp_library_and_content)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ShareLinkRow(icon: ImageVector, title: Int, description: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(description)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
