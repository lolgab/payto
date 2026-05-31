package it.payto.seller

import android.content.Intent
import android.net.Uri
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.luigivampa92.ndefemulation.NdefEmulation
import com.luigivampa92.ndefemulation.ndef.UriNdefData

/** TWA launcher: apre la PWA Cassa (/seller) e gestisce emulazione NFC phone-to-phone. */
class PaytoSellerLauncherActivity : LauncherActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        if (handleSellerNfcIntent(intent)) {
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        if (handleSellerNfcIntent(intent)) {
            finish()
            return
        }
        super.onNewIntent(intent)
    }

    /** payto-seller://nfc?uri=… avvia HCE; payto-seller://nfc-stop lo ferma. */
    private fun handleSellerNfcIntent(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.scheme != "payto-seller") return false

        val emulation = NdefEmulation(this)
        when (data.host) {
            "nfc" -> {
                val uri = data.getQueryParameter("uri") ?: return true
                emulation.currentEmulatedNdefData = UriNdefData(uri)
            }
            "nfc-stop" -> emulation.currentEmulatedNdefData = null
        }
        return true
    }
}
