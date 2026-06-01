package it.payto.seller

import android.content.Intent
import com.google.androidbrowserhelper.trusted.LauncherActivity
import com.google.androidbrowserhelper.trusted.LauncherActivityMetadata
import com.google.androidbrowserhelper.trusted.TwaLauncher
import com.google.androidbrowserhelper.trusted.WebViewFallbackActivity

/**
 * Launcher Cassa: usa sempre WebView fullscreen così payto-seller:// viene intercettato
 * in-process (shouldOverrideUrlLoading) senza uscire dalla PWA né rilanciare Chrome TWA.
 */
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
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
            completionCallback?.run()
        }
    }

    override fun launchTwa() {
        if (isFinishing) return
        val metadata = LauncherActivityMetadata.parse(this)
        val intent = WebViewFallbackActivity.createLaunchIntent(
            this,
            getLaunchingUrl(),
            metadata,
        )
        intent.setClass(this, PaytoSellerWebViewFallbackActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }
}
