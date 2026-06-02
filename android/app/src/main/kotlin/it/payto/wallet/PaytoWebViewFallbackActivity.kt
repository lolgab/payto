package it.payto.wallet

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NfcAdapter
import android.webkit.WebView
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * WebView fullscreen con NFC in foreground: reader mode per HCE phone-to-phone
 * (Realme/OPPO) e foreground dispatch per tag fisici / intent di sistema.
 */
class PaytoWebViewFallbackActivity : WebViewFallbackActivity() {

    private var nfcAdapter: NfcAdapter? = null

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
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return

        val launchPayto = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PaytoLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                addDataScheme("payto")
            },
        )
        adapter.enableForegroundDispatch(this, launchPayto, filters, null)

        adapter.enableReaderMode(
            this,
            { tag ->
                val payto = PaytoNfc.extractPaytoFromTag(tag) ?: return@enableReaderMode
                runOnUiThread { openPaytoInWebView(payto) }
            },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B,
            null,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PaytoNfc.extractPaytoUri(intent)?.let(::openPaytoInWebView)
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        nfcAdapter?.disableForegroundDispatch(this)
        super.onPause()
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
