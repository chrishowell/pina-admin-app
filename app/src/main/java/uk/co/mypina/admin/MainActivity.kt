package uk.co.mypina.admin

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import uk.co.mypina.admin.api.AdminApi
import uk.co.mypina.admin.nfc.TagVerifier
import uk.co.mypina.admin.nfc.TagWriters
import uk.co.mypina.admin.ui.theme.PinaTheme
import uk.co.mypina.admin.ui.ScopedViewModelStore
import uk.co.mypina.admin.verify.VerifyTagScreen
import uk.co.mypina.admin.web.AdminWebScreen
import uk.co.mypina.admin.web.WebState
import uk.co.mypina.admin.web.createAdminWebView
import uk.co.mypina.admin.write.WriteTagScreen

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf<Screen>(Screen.Web)
    private val webState = WebState()
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        webView = createAdminWebView(
            activity = this,
            state = webState,
            onWriteTag = { tagId -> screen = Screen.WriteTag(tagId) },
            onVerifyTag = { tagId -> screen = Screen.VerifyTag(tagId) },
        )
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(AdminConfig.startUrl)
        }

        val api = AdminApi(AdminConfig.baseUrl)

        setContent {
            PinaTheme {
                Box(Modifier.fillMaxSize()) {
                    AdminWebScreen(
                        webView = webView,
                        state = webState,
                        active = screen is Screen.Web,
                    )
                    // Back to the page the screen was opened from, reloaded (e.g. so "Written on …" shows).
                    val backToWeb = {
                        screen = Screen.Web
                        webView.reload()
                    }
                    when (val current = screen) {
                        is Screen.WriteTag -> ScopedViewModelStore(key = current) {
                            WriteTagScreen(
                                tagId = current.tagId,
                                writerFactory = { TagWriters.create(api) },
                                onClose = backToWeb,
                            )
                        }
                        is Screen.VerifyTag -> ScopedViewModelStore(key = current) {
                            VerifyTagScreen(
                                tagId = current.tagId,
                                readUrl = { iso -> TagVerifier.readUrl(iso) },
                                verify = api::verify,
                                onClose = backToWeb,
                            )
                        }
                        Screen.Web -> Unit
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        // Persist the 30-day pina_a session cookie so sign-in survives app restarts.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
