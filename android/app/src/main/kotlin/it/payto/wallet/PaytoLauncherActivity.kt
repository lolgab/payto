package it.payto.wallet

import android.content.Intent
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.LauncherActivityMetadata
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * TWA launcher: payto:// (QR, NFC, link) → https://…/?uri=… via getProtocolHandlers(),
 * come definito anche in manifest.webmanifest protocol_handlers.
 */
class PaytoLauncherActivity : LauncherActivity() {

    override fun getProtocolHandlers(): Map<String, android.net.Uri> {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        return mapOf("payto" to android.net.Uri.parse("$origin/?uri=%s"))
    }

    override fun getFallbackStrategy(): TwaLauncher.FallbackStrategy {
        return TwaLauncher.FallbackStrategy { context, twaBuilder, _, completionCallback ->
            val metadata = LauncherActivityMetadata.parse(context)
            val intent = WebViewFallbackActivity.createLaunchIntent(
                context,
                twaBuilder.uri,
                metadata,
            )
            intent.setClass(context, PaytoWebViewFallbackActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
            completionCallback?.run()
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        PaytoNfc.applyPaytoIntent(intent)
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        PaytoNfc.applyPaytoIntent(intent)
        setIntent(intent)
        super.onNewIntent(intent)
    }
}
