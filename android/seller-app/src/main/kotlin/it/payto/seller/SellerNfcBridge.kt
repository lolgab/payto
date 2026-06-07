package it.payto.seller

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.luigivampa92.ndefemulation.NdefEmulation
import com.luigivampa92.ndefemulation.ndef.UriNdefData

/** Avvia/ferma emulazione HCE phone-to-phone (persiste via SharedPreferences). */
object SellerNfcBridge {

    fun stop(context: Context) {
        NdefEmulation(context.applicationContext).currentEmulatedNdefData = null
    }

    fun handle(context: Context, data: Uri): Boolean {
        if (data.scheme != "payto-seller") return false
        val app = context.applicationContext
        val emulation = NdefEmulation(app)
        when (data.host) {
            "nfc" -> {
                val uri = data.getQueryParameter("uri") ?: return true
                emulation.currentEmulatedNdefData = UriNdefData(uri)
            }
            "nfc-stop" -> emulation.currentEmulatedNdefData = null
            else -> return false
        }
        return true
    }

    fun handleIntent(context: Context, intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return handle(context, data)
    }
}
