package it.payto.seller

import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/** WebView fullscreen con intercept di payto-seller:// per avviare HCE dalla PWA. */
class PaytoSellerWebViewFallbackActivity : WebViewFallbackActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // Nessuna sessione NFC sopravvive al riavvio/chiusura dell'activity.
        SellerNfcBridge.stop(this)
        val httpLaunchUrl = intent.getParcelableExtra<Uri>(LAUNCH_URL_EXTRA)
        if (httpLaunchUrl?.scheme == "http") {
            // WebViewFallbackActivity accetta solo https; in debug usiamo http locale.
            intent.putExtra(
                LAUNCH_URL_EXTRA,
                httpLaunchUrl.buildUpon().scheme("https").build(),
            )
        }
        super.onCreate(savedInstanceState)
        applySafeAreaInsets()
        if (httpLaunchUrl?.scheme == "http") {
            findContentWebView()?.loadUrl(httpLaunchUrl.toString())
        }
        attachSellerBridge()
    }

    override fun onStop() {
        SellerNfcBridge.stop(this)
        super.onStop()
    }

    private fun applySafeAreaInsets() {
        val webView = findContentWebView() ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(webView)
    }

    private fun attachSellerBridge() {
        val webView = findContentWebView() ?: return
        val defaultClient = webView.webViewClient
        webView.webViewClient = object : WebViewClient() {
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (interceptSellerScheme(view, request.url)) return true
                return defaultClient.shouldOverrideUrlLoading(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (interceptSellerScheme(view, Uri.parse(url))) return true
                return defaultClient.shouldOverrideUrlLoading(view, url)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                return defaultClient.onRenderProcessGone(view, detail)
            }
        }
    }

    private fun interceptSellerScheme(view: WebView, uri: Uri): Boolean {
        if (uri.scheme != "payto-seller") return false
        SellerNfcBridge.handle(view.context, uri)
        return true
    }

    private fun findContentWebView(): WebView? {
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return if (content.childCount > 0) content.getChildAt(0) as? WebView else null
    }

    private companion object {
        private const val LAUNCH_URL_EXTRA =
            "com.google.browser.examples.twawebviewfallback.WebViewFallbackActivity.LAUNCH_URL"
    }
}
