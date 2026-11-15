package org.maun.messenger.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * The email login of a member-only relay, and what the app reads back from it.
 *
 * The relay's owner keeps a list of approved email addresses in Cloudflare
 * Access. The app opens the relay's login page in a WebView; Access steps in
 * front of it, sends a one-time code to the address, and after the right code
 * lets the page load. The page then carries a signed token saying which
 * address was proven. That token is all the app takes from the WebView.
 */
object AccessLogin {
    /** The page Cloudflare Access protects. */
    fun loginUrl(relayUrl: String): HttpUrl? =
        relayUrl.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/v1/access/login")?.query(null)?.build()

    /** Whether [url] is the relay's own login page, reached after Access has let the visitor through. */
    fun isLoginPage(url: String?, login: HttpUrl): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return false
        return parsed.isHttps == login.isHttps && parsed.host == login.host && parsed.port == login.port &&
            parsed.encodedPath == login.encodedPath
    }

    /** Whether the WebView may go to [url]: the relay itself and Cloudflare's login pages, nothing else. */
    fun mayVisit(url: String?, login: HttpUrl): Boolean {
        val parsed = url?.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps && login.isHttps) return false
        return parsed.host == login.host || parsed.host.endsWith(".cloudflareaccess.com")
    }

    private val TOKEN = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    /**
     * Reads the token out of what the page's script returned (a JSON string)
     * or out of the cookies Access set for the relay. Null when neither holds
     * something shaped like a token.
     */
    fun tokenFrom(scriptResult: String?, cookies: String?): String? {
        val fromPage = scriptResult
            ?.takeIf { it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") }
            ?.let { it.substring(1, it.length - 1) }
        if (fromPage != null && fromPage.length <= MAX_TOKEN_CHARS && TOKEN.matches(fromPage)) return fromPage
        val fromCookie = cookies?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("CF_Authorization=") }
            ?.removePrefix("CF_Authorization=")
        return fromCookie?.takeIf { it.length <= MAX_TOKEN_CHARS && TOKEN.matches(it) }
    }

    /** A script that writes [email] into the login form's address field, to save typing it twice. */
    fun fillEmailScript(email: String): String =
        "(function(){var f=document.querySelector('input[type=email],input[name=email]');" +
            "if(f&&!f.value){f.value=${JSONObject.quote(email)};" +
            "f.dispatchEvent(new Event('input',{bubbles:true}));}})()"

    const val READ_TOKEN_SCRIPT = "document.body.getAttribute('data-maun-access')"
    private const val MAX_TOKEN_CHARS = 4096

    fun looksLikeEmail(text: String): Boolean {
        val trimmed = text.trim()
        val at = trimmed.indexOf('@')
        return at > 0 && at == trimmed.lastIndexOf('@') && trimmed.indexOf('.', at) > at + 1 &&
            !trimmed.endsWith(".") && trimmed.none { it.isWhitespace() } && trimmed.length <= 254
    }
}

/**
 * Stands in front of the chats while the relay does not know who this is.
 * [problem] says why a login that was just tried did not count. [onToken]
 * receives the proof once Cloudflare Access has handed it over; while the
 * relay is then being asked whether it accepts it, [checking] is true.
 */
@Composable
fun LoginScreen(relayName: String, relayUrl: String, problem: String?, onToken: (String) -> Unit, checking: Boolean = false) {
    var email by rememberSaveable { mutableStateOf("") }
    var browsing by rememberSaveable { mutableStateOf(false) }
    val login = remember(relayUrl) { AccessLogin.loginUrl(relayUrl) }
    val focus = LocalFocusManager.current

    if (browsing && login != null) {
        AccessWebView(
            login = login,
            email = email.trim(),
            onToken = { token ->
                browsing = false
                onToken(token)
            },
            onCancel = { browsing = false },
        )
        return
    }

    val valid = AccessLogin.looksLikeEmail(email)
    val go = {
        if (valid && login != null) {
            focus.clearFocus()
            browsing = true
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                MaunIcons.Mail,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("Log in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "$relayName is for approved members. Enter the email address its owner approved; " +
                    "a one-time code is sent there.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(254) },
                label = { Text("Email address") },
                singleLine = true,
                isError = problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            )
            if (problem != null) {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = go,
                enabled = valid && login != null && !checking,
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            ) {
                Text(if (checking) "Checking…" else "Continue")
            }
            Text(
                text = "You log in once on this phone. After that the phone proves itself with a key that " +
                    "cannot leave it.\n\nThe relay's owner will see this address, the Maun ID it belongs to, " +
                    "and which phone you use: its make, model and a fingerprint of it. Your messages stay " +
                    "unreadable to the relay and its owner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

/**
 * Cloudflare's login pages, and nothing else, in a WebView that starts and
 * ends with no cookies or stored data. Scripts are on because the login page
 * needs them; no bridge into the app is exposed to the page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AccessWebView(login: HttpUrl, email: String, onToken: (String) -> Unit, onCancel: () -> Unit) {
    val currentOnToken by rememberUpdatedState(onToken)
    var loading by remember { mutableStateOf(true) }
    var delivered by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val web = view
        if (web != null && web.canGoBack()) web.goBack() else onCancel()
    }
    DisposableEffect(Unit) {
        onDispose {
            // Leave nothing of the login behind: the token is in the app's own storage now.
            view?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                destroy()
            }
            forgetWebData()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) { Text("Cancel") }
                Text(
                    text = login.host,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    forgetWebData()
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean =
                                !AccessLogin.mayVisit(request.url?.toString(), login)

                            override fun onPageStarted(web: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(web: WebView, url: String?) {
                                loading = false
                                if (delivered) return
                                if (!AccessLogin.isLoginPage(url, login)) {
                                    if (email.isNotEmpty()) web.evaluateJavascript(AccessLogin.fillEmailScript(email), null)
                                    return
                                }
                                // Through: the relay's own page is showing, and it carries the token.
                                web.evaluateJavascript(AccessLogin.READ_TOKEN_SCRIPT) { result ->
                                    val cookies = CookieManager.getInstance().getCookie(login.toString())
                                    val token = AccessLogin.tokenFrom(result, cookies)
                                    if (token != null && !delivered) {
                                        delivered = true
                                        currentOnToken(token)
                                    }
                                }
                            }
                        }
                        view = this
                        loadUrl(login.toString())
                    }
                },
            )
        }
    }
}

private fun forgetWebData() {
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
}

/** Shown, and nothing else, once the relay's owner has shut this member out. */
@Composable
fun BannedScreen(relayName: String, onCheckAgain: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                MaunIcons.Block,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                "You have been banned",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "The owner of $relayName has removed your access. The messages and files on this phone " +
                    "have been deleted. If you think this is a mistake, ask them.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            TextButton(onClick = onCheckAgain) { Text("Check again") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginPreview() {
    MaunTheme { LoginScreen(relayName = "maun.example.workers.dev", relayUrl = "https://maun.example.workers.dev/", problem = null, onToken = {}) }
}

@Preview(showBackground = true)
@Composable
private fun BannedPreview() {
    MaunTheme { BannedScreen(relayName = "maun.example.workers.dev", onCheckAgain = {}) }
}
