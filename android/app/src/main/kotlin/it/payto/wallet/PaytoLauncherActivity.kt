package it.payto.wallet

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Bundle
import com.google.androidbrowserhelper.trusted.LauncherActivity

/**
 * TWA launcher: apre la PWA a schermo intero e converte payto:// in /?uri=… sulla stessa origine.
 * Normalizza gli intent NFC (NDEF_DISCOVERED) impostando data=payto://… prima del launch.
 */
class PaytoLauncherActivity : LauncherActivity() {

    override fun getProtocolHandlers(): Map<String, Uri> {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        return mapOf("payto" to Uri.parse("$origin/?uri=%s"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        normalizeNfcIntent(intent)
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        normalizeNfcIntent(intent)
        setIntent(intent)
        super.onNewIntent(intent)
    }

    private fun normalizeNfcIntent(intent: Intent) {
        if (intent.data != null) return
        extractPaytoUri(intent)?.let { intent.data = it }
    }

    private fun extractPaytoUri(intent: Intent): Uri? {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED != intent.action) return null

        @Suppress("DEPRECATION")
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?: return null

        for (raw in rawMessages) {
            val message = raw as? NdefMessage ?: continue
            for (record in message.records) {
                if (record.tnf != NdefRecord.TNF_WELL_KNOWN) continue
                if (!record.type.contentEquals(NdefRecord.RTD_URI)) continue
                val uri = record.toUri()
                if ("payto" == uri.scheme) return uri
            }
        }
        return null
    }
}
