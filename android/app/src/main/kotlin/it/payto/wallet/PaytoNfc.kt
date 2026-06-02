package it.payto.wallet

import android.content.Intent

/** Supporto NFC minimo: promuove solo un payto:// già presente nell'intent data. */
object PaytoNfc {
    fun extractPaytoUri(intent: Intent): String? {
        intent.data?.let { uri ->
            if ("payto" == uri.scheme) return uri.toString()
        }
        return null
    }

    fun applyPaytoIntent(intent: Intent) {
        if (intent.data != null) return
        extractPaytoUri(intent)?.let { payto ->
            intent.data = android.net.Uri.parse(payto)
        }
    }
}
