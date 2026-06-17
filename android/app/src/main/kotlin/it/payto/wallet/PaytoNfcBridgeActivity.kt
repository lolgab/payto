package it.payto.wallet

import android.app.Activity
import android.os.Bundle

/** Intent esterno payto-wallet:// -> HCE, senza riaprire il launcher. */
class PaytoNfcBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaytoNfcBridge.handleIntent(this, intent)
        PaytoNfcBridge.syncPreferredService(this)
        finish()
    }
}
