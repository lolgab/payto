package it.payto.wallet

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.LauncherActivityMetadata
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * TWA launcher: apre la PWA a schermo intero e converte payto:// in /?uri=… sulla stessa origine.
 * Abilita NFC foreground dispatch quando l'activity è visibile.
 */
class PaytoLauncherActivity : LauncherActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null

    override fun getProtocolHandlers(): Map<String, android.net.Uri> {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        return mapOf("payto" to android.net.Uri.parse("$origin/?uri=%s"))
    }

    override fun getFallbackStrategy(): TwaLauncher.FallbackStrategy {
        return TwaLauncher.FallbackStrategy { context, twaBuilder, _, completionCallback ->
            val metadata = LauncherActivityMetadata.parse(context)
            val intent = WebViewFallbackActivity.createLaunchIntent(
                context,
                twaBuilder.uri,
                metadata,
            )
            intent.setClass(context, PaytoWebViewFallbackActivity::class.java)
            context.startActivity(intent)
            completionCallback?.run()
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        normalizeNfcIntent(intent)
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    override fun onNewIntent(intent: Intent) {
        normalizeNfcIntent(intent)
        setIntent(intent)
        super.onNewIntent(intent)
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

    private fun normalizeNfcIntent(intent: Intent) {
        if (intent.data != null) return
        PaytoNfc.extractPaytoUri(intent)?.let { payto ->
            intent.data = android.net.Uri.parse(payto)
        }
    }
}
