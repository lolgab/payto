package it.payto.wallet

import android.content.Intent
import android.util.Log
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef

/** Estrae payto:// da intent VIEW o NDEF_DISCOVERED (come QR / deep link). */
object PaytoNfc {
    private const val TAG = "PaytoNfc"

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
            val parsed = extractPaytoFromNdefMessage(ndef.ndefMessage)
            Log.d(TAG, "extractPaytoFromTag techs=${tag.techList.joinToString()} parsed=$parsed")
            parsed
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
            // 1) Standard URI record
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI)) {
                val uri = record.toUri()
                if ("payto" == uri.scheme) return uri.toString()
            }

            // 2) Some vendors/libraries expose a textual payload with the full URI.
            val raw = runCatching { String(record.payload, Charsets.UTF_8) }.getOrNull().orEmpty()
            val cleaned = raw.trim().removePrefix("\u0000")
            when {
                cleaned.startsWith("payto://") -> return cleaned
                cleaned.startsWith("payto:") -> return cleaned
                else -> {
                    // 3) Fallback: scan payload bytes for an embedded payto prefix.
                    val idx = cleaned.indexOf("payto://")
                    if (idx >= 0) return cleaned.substring(idx).trim()
                }
            }
        }
        return null
    }
}
