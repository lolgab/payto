package it.payto.wallet

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import com.google.androidbrowserhelper.trusted.LauncherActivity

/**
 * TWA launcher: apre la PWA a schermo intero e converte payto:// in /?uri=… sulla stessa origine.
 * Estrae l'URI anche da intent NFC (NDEF_DISCOVERED) quando getData() è assente.
 */
class PaytoLauncherActivity : LauncherActivity() {

    override fun getProtocolHandlers(): Map<String, Uri> {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        return mapOf("payto" to Uri.parse("$origin/?uri=%s"))
    }

    override fun getUrlForIntent(intent: Intent): Uri? {
        intent.data?.let { return it }
        if (NfcAdapter.ACTION_NDEF_DISCOVERED != intent.action) return null

        @Suppress("DEPRECATION")
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?: return null

        for (raw in rawMessages) {
            val message = raw as? NdefMessage ?: continue
            for (record in message.records) {
                if (record.tnf != NdefRecord.TNF_WELL_KNOWN) continue
                if (!record.type.contentEquals(NdefRecord.RTD_URI)) continue
                return record.toUri()
            }
        }
        return null
    }
}
