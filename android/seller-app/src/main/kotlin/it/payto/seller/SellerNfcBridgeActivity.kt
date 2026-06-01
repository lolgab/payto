package it.payto.seller

import android.app.Activity
import android.os.Bundle

/** Intent esterno payto-seller:// → HCE, senza toccare il launcher TWA/WebView. */
class SellerNfcBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SellerNfcBridge.handleIntent(this, intent)
        finish()
    }
}
