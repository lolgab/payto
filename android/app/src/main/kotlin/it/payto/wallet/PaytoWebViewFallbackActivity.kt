package it.payto.wallet

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NfcAdapter
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * WebView fullscreen. Se PayTo è già aperto e arriva un tag NFC con payto://,
 * inoltra a [PaytoLauncherActivity] — stesso percorso di QR / deep link.
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
            val content = window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            (content?.getChildAt(0) as? android.webkit.WebView)?.loadUrl(httpLaunchUrl.toString())
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
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private companion object {
        private const val LAUNCH_URL_EXTRA =
            "com.google.browser.examples.twawebviewfallback.WebViewFallbackActivity.LAUNCH_URL"
    }
}
