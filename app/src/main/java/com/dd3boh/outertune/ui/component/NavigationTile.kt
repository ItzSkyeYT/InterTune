package com.dd3boh.outertune.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.ui.utils.canWrap
import com.dd3boh.outertune.ui.utils.shrinkToFit

@Composable
fun NavigationTile(
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))
                .clickable(onClick = onClick)
                // The button is the circle, and its name is written under it, outside it: a
                // screen reader had a button with nothing to say, then a word that did nothing.
                .semantics { contentDescription = title }
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null
            )
        }

        TileTitle(title)
    }
}

/**
 * [beside] puts the title beside the icon instead of under it, in one pill that is the button:
 * for a window too short to give a row of four buttons a hundred dp of its height, which is a
 * phone on its side (Landscape.kt).
 */
@Composable
fun NavigationTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    beside: Boolean = false,
) {
    if (beside) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))
                .clickable(onClick = onClick)
                .height(40.dp)
                .padding(start = 12.dp, end = 16.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))
                .clickable(onClick = onClick)
                // The button is the circle, and its name is written under it, outside it: a
                // screen reader had a button with nothing to say, then a word that did nothing.
                .semantics { contentDescription = title }
        ) {
            Icon(
                icon,
                contentDescription = null
            )
        }

        TileTitle(title)
    }
}

/**
 * A tile's title, under its button.
 *
 * Two lines for a title wider than its quarter of the row: on a phone 393dp wide "Local scanner"
 * is, and was cut to "Local scann...". The button above it stays where it is, and only a row
 * that has such a title is a line taller.
 *
 * A word is never broken to make the second line. In French at a large font size "Statistiques"
 * is wider than the tile, and was drawn as "Statistiqu" over "es": the title gets smaller until
 * its words are whole, and a title of one word keeps to one line. Only a word still too wide at
 * three quarters of the size is cut.
 */
@Composable
private fun TileTitle(title: String) {
    val style = MaterialTheme.typography.labelMedium
    Text(
        text = title,
        style = style,
        autoSize = style.shrinkToFit(smallest = 9.sp),
        textAlign = TextAlign.Center,
        maxLines = if (remember(title) { canWrap(title) }) 2 else 1,
        overflow = TextOverflow.Ellipsis
    )
}
