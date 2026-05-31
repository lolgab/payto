package it.payto.wallet

import android.net.Uri
import com.google.androidbrowserhelper.trusted.LauncherActivity

/**
 * TWA launcher: apre la PWA a schermo intero e converte payto:// in /?uri=… sulla stessa origine.
 */
class PaytoLauncherActivity : LauncherActivity() {

    override fun getProtocolHandlers(): Map<String, Uri> {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        return mapOf("payto" to Uri.parse("$origin/?uri=%s"))
    }
}
