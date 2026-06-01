package it.payto.wallet

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef

/** Estrae payto:// da intent VIEW o NDEF_DISCOVERED (come QR / deep link). */
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

    fun applyPaytoIntent(intent: Intent) {
        if (intent.data != null) return
        extractPaytoUri(intent)?.let { payto ->
            intent.data = android.net.Uri.parse(payto)
        }
    }

    /** Lettura phone-to-phone (HCE): il reader mode consegna un [Tag] invece di un intent. */
    fun extractPaytoFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            extractPaytoFromNdefMessage(ndef.ndefMessage)
        } catch (_: Exception) {
            null
        } finally {
            try {
                ndef.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractPaytoFromNdefMessage(message: NdefMessage?): String? {
        if (message == null) return null
        for (record in message.records) {
            if (record.tnf != NdefRecord.TNF_WELL_KNOWN) continue
            if (!record.type.contentEquals(NdefRecord.RTD_URI)) continue
            val uri = record.toUri()
            if ("payto" == uri.scheme) return uri.toString()
        }
        return null
    }
}
