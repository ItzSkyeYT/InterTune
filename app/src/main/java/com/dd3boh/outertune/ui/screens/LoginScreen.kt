package com.dd3boh.outertune.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(
    navController: NavController,
) {
    var visitorData by rememberPreference(VisitorDataKey, "")
    var dataSyncId by rememberPreference(DataSyncIdKey, "")
    var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    var accountName by rememberPreference(AccountNameKey, "")
    var accountEmail by rememberPreference(AccountEmailKey, "")
    var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")

    var webView: WebView? = null
    // The page actually showing, for the address above it. A web view with no address is a box
    // that asks for your Google password, and there is no way to tell it from one that is not
    // Google's; the address and its lock are what a browser would show you.
    var currentUrl by remember { mutableStateOf(LOGIN_URL) }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
    ) {
        LoginAddressBar(currentUrl)
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            if (url != null) currentUrl = url
                        }

                        // Google's sign-in moves between steps without loading a new page, so the
                        // address is also read from the history it pushes.
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            if (url != null) currentUrl = url
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
                            loadUrl("javascript:Android.onRetrieveDataSyncId(window.yt.config_.DATASYNC_ID)")

                            if (url?.startsWith("https://music.youtube.com") == true) {
                                innerTubeCookie = CookieManager.getInstance().getCookie(url)
                                GlobalScope.launch {
                                    YouTube.accountInfo().onSuccess {
                                        accountName = it.name
                                        accountEmail = it.email.orEmpty()
                                        accountChannelHandle = it.channelHandle.orEmpty()
                                    }.onFailure {
                                        reportException(it)
                                    }
                                }
                            }
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                    }
                    // Asked for rather than left to the default, so a password manager, Google's
                    // included, offers to fill the sign-in: one tap instead of typing a password.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onRetrieveVisitorData(newVisitorData: String?) {
                            if (newVisitorData != null) {
                                visitorData = newVisitorData
                            }
                        }
                        @JavascriptInterface
                        fun onRetrieveDataSyncId(newDataSyncId: String?) {
                            if (newDataSyncId != null) {
                                dataSyncId = newDataSyncId.substringBefore("||")
                            }
                        }
                    }, "Android")
                    webView = this
                    loadUrl(LOGIN_URL)
                }
            }
        )
    }

    FloatingTopBar(title = stringResource(R.string.login), navController = navController)

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

/**
 * The address of the page being shown, with a lock when the connection is encrypted, and one line
 * on what InterTune keeps. Only the host: the full sign-in address is a screen of parameters that
 * says nothing more about whose page it is.
 */
@Composable
private fun LoginAddressBar(url: String) {
    val uri = remember(url) { Uri.parse(url) }
    val secure = uri.scheme.equals("https", ignoreCase = true)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (secure) Icons.Rounded.Lock else Icons.Rounded.Warning,
                    contentDescription = stringResource(if (secure) R.string.login_secure else R.string.login_not_secure),
                    tint = if (secure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = uri.host ?: url,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.login_reassurance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
