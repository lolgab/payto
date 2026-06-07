package it.payto.wallet

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef

/** Lettura payto:// da intent NFC (deep link o NDEF) e foreground dispatch. */
object PaytoNfc {
    fun extractPaytoUri(intent: Intent): String? {
        intent.data?.let { uri ->
            if ("payto" == uri.scheme) return uri.toString()
        }
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (parcelable in messages) {
            val message = parcelable as? NdefMessage ?: continue
            for (record in message.records) {
                record.toUri()?.toString()?.let { uri ->
                    if (uri.startsWith("payto:")) return uri
                }
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

    fun createForegroundDispatch(activity: Activity): ForegroundDispatch? {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
        return ForegroundDispatch(activity, adapter)
    }

    class ForegroundDispatch(
        private val activity: Activity,
        private val adapter: NfcAdapter,
    ) {
        private val pendingIntent: PendingIntent = PendingIntent.getActivity(
            activity,
            0,
            Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        private val intentFilters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                addDataScheme("payto")
            },
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
        )

        private val techLists = arrayOf(
            arrayOf(Ndef::class.java.name),
        )

        fun enable() {
            adapter.enableForegroundDispatch(activity, pendingIntent, intentFilters, techLists)
        }

        fun disable() {
            adapter.disableForegroundDispatch(activity)
        }
    }
}
