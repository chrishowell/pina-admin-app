package uk.co.mypina.admin.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import uk.co.mypina.admin.AdminConfig
import uk.co.mypina.admin.BuildConfig

/** Observable bits of the web view that Compose needs. */
class WebState {
    var canGoBack by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
}

private const val SCHEME = "pina-admin"
private const val WRITE_TAG_HOST = "write-tag"
private const val VERIFY_TAG_HOST = "verify-tag"
private const val IDENTIFY_TAG_HOST = "identify-tag"
private val TAG_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * Builds the one WebView the app uses. It's owned by the activity (not by composition) so its
 * history survives the trip to a native screen (Write, Verify, Identify) and back.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createAdminWebView(
    activity: Activity,
    state: WebState,
    onWriteTag: (tagId: String) -> Unit,
    onVerifyTag: (tagId: String?) -> Unit,
    onIdentifyTag: () -> Unit,
): WebView {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    val webView = WebView(activity)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        userAgentString = WebSettings.getDefaultUserAgent(activity) + AdminConfig.userAgentSuffix
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }

    webView.webChromeClient = object : WebChromeClient() {
        // Default JS dialogs (confirm() before deleting a tag, etc.) need a WebChromeClient.
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            state.progress = newProgress
        }
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()

            if (scheme == SCHEME) {
                val tagId = uri.pathSegments.firstOrNull()
                when (uri.host) {
                    // pina-admin://write-tag/<tagId>
                    WRITE_TAG_HOST -> if (tagId != null && TAG_ID.matches(tagId)) onWriteTag(tagId)
                    // pina-admin://verify-tag or pina-admin://verify-tag/<tagId>
                    VERIFY_TAG_HOST -> when {
                        tagId == null -> onVerifyTag(null)
                        TAG_ID.matches(tagId) -> onVerifyTag(tagId)
                    }
                    // pina-admin://identify-tag (exactly; no id)
                    IDENTIFY_TAG_HOST -> if (tagId == null) onIdentifyTag()
                }
                return true
            }

            if (scheme == "http" || scheme == "https") {
                // Sub-frames (embeds) load in place; only top-level navigations leave the app.
                if (!request.isForMainFrame || AdminConfig.isAdminHost(uri.host)) return false
            }

            // Off the admin host (mypina.co.uk receipts, instagram.com, mailto:, tel:, ...).
            openExternally(activity, uri)
            return true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            state.canGoBack = view.canGoBack()
        }
    }

    return webView
}

private fun openExternally(activity: Activity, uri: Uri) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; ignore.
    }
}

@Composable
fun AdminWebScreen(webView: WebView, state: WebState, active: Boolean) {
    // Back walks the web view's history; at the root it falls through and the app exits.
    BackHandler(enabled = active && state.canGoBack) { webView.goBack() }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
        )
        if (state.progress in 1..99) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
