package it.payto.wallet

import android.content.Intent
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.LauncherActivityMetadata
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * Launcher wallet: supporta solo payto:// come deep link in ingresso
 * (incluso quando Android risolve un NDEF_DISCOVERED con schema payto).
 */
class PaytoLauncherActivity : LauncherActivity() {

    override fun getProtocolHandlers(): Map<String, android.net.Uri> {
        val origin = BuildConfig.WEB_ORIGIN.trimEnd('/')
        return mapOf("payto" to android.net.Uri.parse("$origin/?uri=%s"))
    }

    override fun getFallbackStrategy(): TwaLauncher.FallbackStrategy {
        return TwaLauncher.FallbackStrategy { context, twaBuilder, _, completionCallback ->
            launchWalletWebView(context, twaBuilder.uri)
            completionCallback?.run()
        }
    }

    override fun launchTwa() {
        if (isFinishing) return
        launchWalletWebView(this, getLaunchingUrl())
        finish()
    }

    private fun launchWalletWebView(
        context: android.content.Context,
        url: android.net.Uri,
    ) {
        val metadata = LauncherActivityMetadata.parse(context)
        val intent = WebViewFallbackActivity.createLaunchIntent(context, url, metadata)
        intent.setClass(context, PaytoWebViewFallbackActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
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
