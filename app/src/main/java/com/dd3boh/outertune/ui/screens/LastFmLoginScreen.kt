package com.dd3boh.outertune.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.LocalScrobbler
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import kotlinx.coroutines.launch

/**
 * Last.fm login, in the app rather than in a browser.
 *
 * Same shape as [LoginScreen]: a WebView showing the service's own page, so the password is typed
 * into Last.fm and never into InterTune, and the user never leaves the app. The difference is what
 * comes back. YouTube leaves a cookie to read; Last.fm instead redirects once the user approves,
 * and the token InterTune already holds becomes valid at that moment.
 *
 * So there is nothing to scrape. The screen watches for the redirect away from the approval page,
 * exchanges the token for a session key, and closes itself. If the user backs out instead, nothing
 * was stored and the token expires on its own.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LastFmLoginScreen(
    navController: NavController,
) {
    val scrobbler = LocalScrobbler.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    var authorizeUrl by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Ask for the token before showing anything, since the page to load is built from it.
    LaunchedEffect(Unit) {
        scrobbler.beginLogin()
            .onSuccess { (t, url) ->
                token = t
                authorizeUrl = url
            }
            .onFailure { error = it.message ?: "" }
    }

    val message = error
    val url = authorizeUrl
    if (message != null) {
        // Closing the screen without a word was the old behaviour, and it made a rejected API key
        // look like a dead button. Say what happened instead.
        Box(
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.lastfm_login_failed, message),
                textAlign = TextAlign.Center,
            )
        }
    } else if (url != null) {
        AndroidView(
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            if (finishing) return
                            val t = token ?: return
                            // Do not try to read approval out of the URL. Last.fm shows "Application
                            // authenticated" on the same /api/auth address rather than redirecting,
                            // so watching for a redirect waits forever and the screen just sits
                            // there.
                            //
                            // Ask instead. auth.getSession answers with a session once the user has
                            // approved and an error until then, so attempting it on each page load
                            // costs one cheap request and needs no guesswork. An unapproved token is
                            // not consumed by the attempt, so retrying is safe.
                            scope.launch {
                                scrobbler.completeLogin(t)
                                    .onSuccess {
                                        finishing = true
                                        navController.navigateUp()
                                    }
                            }
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    loadUrl(url)
                }
            }
        )
    }

    // Drawn after the branch rather than inside it. The three states here are an error, a blank
    // wait for the token, and the page itself, and the first two used to return early, so the
    // screen could sit with nothing on it and no way off but the system gesture.
    TopAppBar(
        title = { Text(stringResource(R.string.lastfm_login_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
    )
}
