package com.dd3boh.outertune.ui.screens

import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    // Remembered: as a plain local it was a new, empty holder on every recomposition, so Back never
    // reached the page. Whether the page can go back is kept beside it, taken from the page's own
    // history each time it changes, since Compose cannot watch the web view's answer.
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    // The page actually showing, for the address above it. A web view with no address is a box
    // that asks for your Google password, and there is no way to tell it from one that is not
    // Google's; the address and its lock are what a browser would show you.
    var currentUrl by remember { mutableStateOf(LOGIN_URL) }

    // Most people signing in already have their Google account on this phone. The app cannot use
    // that session itself, since YouTube Music takes only a sign-in made on Google's page, but it
    // can ask Android which account to use, with the system's own picker (no permission, and no
    // Play services), and open Google's page with that address already filled in. Google then
    // mostly offers to confirm on this same phone, or a password manager fills the rest.
    // Null until the picker has answered; empty when there is no address to fill in, which opens
    // the page as it always was.
    val context = LocalContext.current
    var pickedAccount by rememberSaveable { mutableStateOf<String?>(null) }
    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        } else null
        pickedAccount = accountAfterPicker(pickedAccount, picked)
    }
    // A phone without Google's services has no such account type, and would be sent to an empty
    // picker. Asking which types exist needs no permission.
    val canPick = remember {
        runCatching { AccountManager.get(context).authenticatorTypes.any { it.type == GOOGLE_ACCOUNT_TYPE } }
            .getOrDefault(false)
    }
    // Always shown, even with a single account on the phone: somebody who keeps two accounts, or
    // is here to switch, picks every time rather than being signed in as whichever one is there.
    fun openPicker() {
        val intent = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE_ACCOUNT_TYPE), null, null, null, null)
                    .putExtra("alwaysPromptForAccount", true)
            } else {
                @Suppress("DEPRECATION")
                AccountManager.newChooseAccountIntent(null, null as ArrayList<Account>?, arrayOf(GOOGLE_ACCOUNT_TYPE), true, null, null, null, null)
            }
        }.getOrNull()
        if (intent == null) pickedAccount = pickedAccount ?: ""
        else runCatching { accountPicker.launch(intent) }.onFailure { pickedAccount = pickedAccount ?: "" }
    }
    LaunchedEffect(Unit) {
        if (pickedAccount != null) return@LaunchedEffect
        if (canPick) openPicker() else pickedAccount = ""
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
    ) {
        LoginAddressBar(currentUrl)
        val email = pickedAccount
        if (canPick && email != null) {
            LoginAccountRow(
                email = email,
                onSwitch = { openPicker() },
                onAnotherAccount = { pickedAccount = "" },
            )
        }
        // Held back until the picker has answered, so the page loads once, with the address in it.
        // Each later answer, from Switch, Another account or Pick one, gets a page of its own, so
        // Back cannot return to steps taken for the account before, under the new one's name.
        if (email != null) key(email) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                // A page given up for another account says nothing about this one.
                                if (view !== webView) return
                                if (url != null) currentUrl = url
                            }

                            // Google's sign-in moves between steps without loading a new page, so
                            // the address is also read from the history it pushes.
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                if (view !== webView) return
                                if (url != null) currentUrl = url
                                canGoBack = view.canGoBack()
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (view !== webView) return
                                canGoBack = view.canGoBack()
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
                        // Asked for rather than left to the default, so a password manager,
                        // Google's included, offers to fill the sign-in: one tap instead of
                        // typing a password.
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
                        canGoBack = false
                        loadUrl(loginUrl(email))
                    }
                },
                // Closed rather than left running unseen, when it is given up for another account
                // or the screen is left: Google's page for the account before could otherwise go
                // on waiting for a confirmation on the phone.
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.destroy()
                },
            )
        }
    }

    FloatingTopBar(title = stringResource(R.string.login), navController = navController)

    BackHandler(enabled = canGoBack) {
        // Asked again, so an answer gone stale cannot keep Back on this screen.
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else navController.navigateUp()
    }
}

private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/** Google's sign-in, with the address to sign in as filled in when one was picked. */
private fun loginUrl(email: String): String =
    if (email.isEmpty()) LOGIN_URL else LOGIN_URL + "&Email=" + Uri.encode(email)

/**
 * The account Google's page is for once the picker has answered [picked], which is null or empty
 * when it was dismissed or came back without one. A dismissed Switch keeps the account picked
 * before it rather than dropping it; the first picker dismissed opens the page with nothing filled
 * in.
 */
internal fun accountAfterPicker(previous: String?, picked: String?): String =
    if (!picked.isNullOrEmpty()) picked else previous ?: ""

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

/**
 * Which account the page was opened for, with the two ways out of it: Switch opens the phone's
 * account picker again, Another account opens Google's page with nothing filled in, for an account
 * that is not on this phone. Nothing is said when the picker was dismissed, since the page is then
 * Google's own, blank, as it always was.
 */
@Composable
private fun LoginAccountRow(email: String, onSwitch: () -> Unit, onAnotherAccount: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp),
    ) {
        Text(
            text = if (email.isNotEmpty()) stringResource(R.string.login_signing_in_as, email)
            else stringResource(R.string.login_no_account_picked),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSwitch) {
            Text(stringResource(if (email.isNotEmpty()) R.string.login_switch_account else R.string.login_pick_account))
        }
        if (email.isNotEmpty()) {
            TextButton(onClick = onAnotherAccount) {
                Text(stringResource(R.string.login_another_account))
            }
        }
    }
}
