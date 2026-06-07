package it.payto.wallet

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import java.io.IOException

/** Lettura payto:// da intent NFC (deep link o NDEF), foreground dispatch + reader mode. */
object PaytoNfc {
    private val OPPO_MANUFACTURERS = setOf("oppo", "realme", "oneplus")

    fun extractPaytoUri(intent: Intent): String? {
        intent.data?.let { uri ->
            if ("payto" == uri.scheme) return uri.toString()
        }
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            extractPaytoUri(tag)?.let { return it }
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

    fun createNfcReader(
        activity: Activity,
        onPaytoUri: (String) -> Unit,
    ): NfcReader? {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
        return NfcReader(activity, adapter, onPaytoUri)
    }

    private fun paytoFromMessage(message: NdefMessage): String? {
        for (record in message.records) {
            record.toUri()?.toString()?.let { uri ->
                if (uri.startsWith("payto:")) return uri
            }
        }
        return null
    }

    private fun isOppoFamily(): Boolean =
        Build.MANUFACTURER.lowercase() in OPPO_MANUFACTURERS

    class NfcReader(
        private val activity: Activity,
        private val adapter: NfcAdapter,
        private val onPaytoUri: (String) -> Unit,
    ) {
        private val foreground = ForegroundDispatch(activity, adapter)
        private var readerModeEnabled = false

        private val readerCallback = NfcAdapter.ReaderCallback { tag ->
            extractPaytoUri(tag)?.let { payto ->
                activity.runOnUiThread { onPaytoUri(payto) }
            }
        }

        private val readerFlags =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        fun enable() {
            // Reader mode e foreground dispatch insieme fanno crashare NFC su Oppo; uno alla volta.
            if (isOppoFamily()) {
                foreground.enable()
            } else {
                try {
                    adapter.enableReaderMode(activity, readerCallback, readerFlags, null)
                    readerModeEnabled = true
                } catch (_: Exception) {
                    foreground.enable()
                }
            }
        }

        fun disable() {
            if (readerModeEnabled) {
                try {
                    adapter.disableReaderMode(activity)
                } catch (_: Exception) {
                }
                readerModeEnabled = false
            } else {
                foreground.disable()
            }
        }
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
