package it.payto.wallet

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * WebView fullscreen con NFC foreground dispatch: quando la cassa emula un tag HCE
 * e il telefono cliente ha PayTo in primo piano, riceve l'URI payto://.
 */
class PaytoWebViewFallbackActivity : WebViewFallbackActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        deliverPaytoFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverPaytoFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val pending = nfcPendingIntent ?: PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).also { nfcPendingIntent = it }

        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                addDataScheme("payto")
            },
        )
        adapter.enableForegroundDispatch(this, pending, filters, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun deliverPaytoFromIntent(intent: Intent?) {
        val payto = PaytoNfc.extractPaytoUri(intent) ?: return
        val webView = findContentWebView() ?: return
        val headers = mapOf("Referer" to "android-app://$packageName/")
        webView.loadUrl(PaytoNfc.toWebLaunchUrl(payto), headers)
    }

    private fun findContentWebView(): WebView? {
        val content = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return if (content.childCount > 0) content.getChildAt(0) as? WebView else null
    }
}
