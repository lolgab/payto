package it.payto.wallet

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import java.io.IOException

/** Lettura payto:// da tag NFC, intent NDEF e reader mode in foreground. */
object PaytoNfc {
    fun extractPaytoUri(intent: Intent): String? {
        intent.data?.let { uri ->
            if ("payto" == uri.scheme) return uri.toString()
        }
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (parcelable in messages) {
            val message = parcelable as? NdefMessage ?: continue
            paytoFromMessage(message)?.let { return it }
        }
        return null
    }

    fun extractPaytoUri(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            ndef.ndefMessage?.let(::paytoFromMessage)
        } catch (_: IOException) {
            null
        } finally {
            try {
                ndef.close()
            } catch (_: IOException) {
            }
        }
    }

    fun applyPaytoIntent(intent: Intent) {
        if (intent.data != null) return
        extractPaytoUri(intent)?.let { payto ->
            intent.data = android.net.Uri.parse(payto)
        }
    }

    fun createReaderMode(
        activity: Activity,
        onPaytoUri: (String) -> Unit,
    ): ReaderMode? {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
        return ReaderMode(activity, adapter, onPaytoUri)
    }

    private fun paytoFromMessage(message: NdefMessage): String? {
        for (record in message.records) {
            record.toUri()?.toString()?.let { uri ->
                if (uri.startsWith("payto:")) return uri
            }
        }
        return null
    }

    class ReaderMode(
        private val activity: Activity,
        private val adapter: NfcAdapter,
        private val onPaytoUri: (String) -> Unit,
    ) {
        private val callback = NfcAdapter.ReaderCallback { tag ->
            extractPaytoUri(tag)?.let { payto ->
                activity.runOnUiThread { onPaytoUri(payto) }
            }
        }

        private val flags =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        fun enable() {
            adapter.enableReaderMode(activity, callback, flags, null)
        }

        fun disable() {
            adapter.disableReaderMode(activity)
        }
    }
}
