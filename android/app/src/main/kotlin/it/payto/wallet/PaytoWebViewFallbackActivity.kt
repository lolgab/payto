package it.payto.wallet

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * WebView fullscreen minimale: il wallet gestisce solo deep link payto://.
 */
class PaytoWebViewFallbackActivity : WebViewFallbackActivity() {

    private var nfcForeground: PaytoNfc.ForegroundDispatch? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val httpLaunchUrl = intent.getParcelableExtra<Uri>(LAUNCH_URL_EXTRA)
        if (httpLaunchUrl?.scheme == "http") {
            intent.putExtra(
                LAUNCH_URL_EXTRA,
                httpLaunchUrl.buildUpon().scheme("https").build(),
            )
        }
        super.onCreate(savedInstanceState)
        if (httpLaunchUrl?.scheme == "http") {
            findContentWebView()?.loadUrl(httpLaunchUrl.toString())
        }
        nfcForeground = PaytoNfc.createForegroundDispatch(this)
    }

    override fun onResume() {
        super.onResume()
        nfcForeground?.enable()
    }

    override fun onPause() {
        nfcForeground?.disable()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverPaytoUri(intent)
    }

    private fun deliverPaytoUri(intent: Intent) {
        PaytoNfc.extractPaytoUri(intent)?.let(::openPaytoInWebView)
    }

    private fun openPaytoInWebView(payto: String) {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        val url = "$origin/?uri=${Uri.encode(payto)}"
        findContentWebView()?.loadUrl(url)
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
