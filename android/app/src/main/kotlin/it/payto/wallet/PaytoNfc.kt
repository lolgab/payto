package it.payto.wallet

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter

object PaytoNfc {

    fun extractPaytoUri(intent: Intent?): String? {
        if (intent == null) return null
        intent.data?.let { uri ->
            if ("payto" == uri.scheme) return uri.toString()
        }
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
                if ("payto" == uri.scheme) return uri.toString()
            }
        }
        return null
    }

    fun toWebLaunchUrl(paytoUri: String): String {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        return "$origin/?uri=${java.net.URLEncoder.encode(paytoUri, Charsets.UTF_8.name())}"
    }
}
