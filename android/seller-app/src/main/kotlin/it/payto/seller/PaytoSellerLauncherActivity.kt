package it.payto.seller

import android.content.Intent
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.LauncherActivityMetadata
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/** TWA launcher: apre la PWA Cassa (/seller) e gestisce emulazione NFC phone-to-phone. */
class PaytoSellerLauncherActivity : LauncherActivity() {

    override fun getFallbackStrategy(): TwaLauncher.FallbackStrategy {
        return TwaLauncher.FallbackStrategy { context, twaBuilder, _, completionCallback ->
            val metadata = LauncherActivityMetadata.parse(context)
            val intent = WebViewFallbackActivity.createLaunchIntent(
                context,
                twaBuilder.uri,
                metadata,
            )
            intent.setClass(context, PaytoSellerWebViewFallbackActivity::class.java)
            context.startActivity(intent)
            completionCallback?.run()
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        SellerNfcBridge.handleIntent(this, intent)
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        SellerNfcBridge.handleIntent(this, intent)
        setIntent(intent)
        super.onNewIntent(intent)
    }
}
