package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.Throttle
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Says out loud that YouTube is refusing this connection, for as long as that lasts.
 *
 * Until now the back-off was completely invisible: Throttle.blocked and secondsRemaining existed
 * and nothing read them, so from the user's side the home screen simply stopped filling, downloads
 * stopped and sync stopped, with no explanation and no idea that it ends on its own. The countdown
 * is the useful half, since the one question worth answering is "how long".
 */
@Composable
fun ThrottleBanner(modifier: Modifier = Modifier) {
    val blocked by Throttle.blocked.collectAsState()
    if (!blocked) return

    var secondsLeft by remember { mutableLongStateOf(Throttle.secondsRemaining) }

    // Woken only when the number on screen would change, and only while somebody can see it. This
    // used to read the clock every second to drive text that shows whole minutes, sixty wakeups
    // for each change anyone could notice, and a bare LaunchedEffect is not stopped when the
    // screen goes off: this sits in a lazy item on Home, which stays composed in the background,
    // so a throttle that began with Home open ticked through the whole back off in a pocket.
    // Sleeping loses nothing, since secondsRemaining is worked out from elapsedRealtime on every
    // read, and the first read after ON_START is already exact. STARTED for the same reason as
    // the progress bar in Player.kt: still visible behind a dialog.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(blocked, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val left = Throttle.secondsRemaining
                secondsLeft = left
                // ceil(left / 60) drops by one when left reaches the next multiple of sixty, which
                // is left % 60 seconds away, or a whole minute when it is sitting on one already.
                // secondsRemaining rounds down, so this can wake late but never early, and a late
                // wake just shortens the next sleep.
                val untilNextMinute = (left % 60).let { if (it == 0L) 60L else it }
                delay(untilNextMinute * 1000)
            }
        }
    }
    val minutes = ceil(secondsLeft / 60.0).toInt().coerceAtLeast(1)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.throttle_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = pluralStringResource(R.plurals.throttle_banner_body, minutes, minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
