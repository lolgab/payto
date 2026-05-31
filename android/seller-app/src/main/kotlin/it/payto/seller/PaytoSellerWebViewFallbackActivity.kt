package it.payto.seller

import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/** WebView fullscreen con intercept di payto-seller:// per avviare HCE dalla PWA. */
class PaytoSellerWebViewFallbackActivity : WebViewFallbackActivity() {

    override fun createWebViewClient(): WebViewClient {
        val base = super.createWebViewClient()
        return object : WebViewClient() {
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (interceptSellerScheme(view, request.url)) return true
                return base.shouldOverrideUrlLoading(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (interceptSellerScheme(view, Uri.parse(url))) return true
                return base.shouldOverrideUrlLoading(view, url)
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                return base.onRenderProcessGone(view, detail)
            }
        }
    }

    private fun interceptSellerScheme(view: WebView, uri: Uri): Boolean {
        if (uri.scheme != "payto-seller") return false
        SellerNfcBridge.handle(view.context, uri)
        return true
    }
}
