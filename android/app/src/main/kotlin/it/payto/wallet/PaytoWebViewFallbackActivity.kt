package it.payto.wallet

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * WebView fullscreen minimale: il wallet gestisce solo deep link payto://.
 */
class PaytoWebViewFallbackActivity : WebViewFallbackActivity() {

    private var nfcReader: PaytoNfc.ReaderMode? = null
    private var lastPaytoUri: String? = null
    private var lastPaytoAtMs: Long = 0

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val httpLaunchUrl = intent.getParcelableExtra<Uri>(LAUNCH_URL_EXTRA)
        if (httpLaunchUrl?.scheme == "http") {
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
        nfcReader = PaytoNfc.createReaderMode(this, ::deliverPaytoUri)
    }

    override fun onResume() {
        super.onResume()
        nfcReader?.enable()
    }

    override fun onPause() {
        nfcReader?.disable()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverIncomingIntent(intent)
    }

    private fun deliverIncomingIntent(intent: Intent) {
        PaytoNfc.extractPaytoUri(intent)?.let(::deliverPaytoUri)
        intent.getParcelableExtra<Uri>(LAUNCH_URL_EXTRA)?.let(::deliverLaunchUrl)
    }

    private fun deliverLaunchUrl(launchUrl: Uri) {
        val uriParam = launchUrl.getQueryParameter("uri")
        if (uriParam != null) {
            deliverPaytoUri(uriParam)
            return
        }
        findContentWebView()?.loadUrl(launchUrl.toString())
    }

    private fun deliverPaytoUri(payto: String) {
        val now = System.currentTimeMillis()
        if (payto == lastPaytoUri && now - lastPaytoAtMs < 2_000) return
        lastPaytoUri = payto
        lastPaytoAtMs = now
        openPaytoInWebView(payto)
    }

    private fun openPaytoInWebView(payto: String) {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        val url = "$origin/?uri=${Uri.encode(payto)}"
        findContentWebView()?.loadUrl(url)
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

    private fun findContentWebView(): WebView? {
        val content = window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            ?: return null
        return if (content.childCount > 0) content.getChildAt(0) as? WebView else null
    }

    private companion object {
        private const val LAUNCH_URL_EXTRA =
            "com.google.browser.examples.twawebviewfallback.WebViewFallbackActivity.LAUNCH_URL"
    }
}
