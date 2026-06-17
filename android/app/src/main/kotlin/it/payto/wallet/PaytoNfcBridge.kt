package it.payto.wallet

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.util.Log
import com.luigivampa92.ndefemulation.NdefEmulation
import com.luigivampa92.ndefemulation.ndef.UriNdefData

/** Avvia/ferma emulazione HCE phone-to-phone per condividere payto:// dal wallet. */
object PaytoNfcBridge {
    private const val TAG = "PaytoNfcBridge"
    private const val HCE_SERVICE =
        "com.luigivampa92.ndefemulation.hce.NfcType4TagNdefEmulationService"

    private var preferredActivity: Activity? = null

    fun stop(context: Context) {
        Log.d(TAG, "stop HCE")
        clearPreferredService(context)
        NdefEmulation(context.applicationContext).currentEmulatedNdefData = null
    }

    fun syncPreferredService(activity: Activity) {
        val app = activity.applicationContext
        val emulation = NdefEmulation(app)
        if (emulation.currentEmulatedNdefData == null) {
            clearPreferredService(activity)
            return
        }
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        val cardEmulation = CardEmulation.getInstance(adapter)
        if (!cardEmulation.isDefaultServiceForCategory(
                ComponentName(app, HCE_SERVICE),
                CardEmulation.CATEGORY_OTHER,
            )
        ) {
            val component = ComponentName(app.packageName, HCE_SERVICE)
            if (cardEmulation.setPreferredService(activity, component)) {
                preferredActivity = activity
                Log.i(TAG, "HCE preferred service set")
            }
        }
    }

    fun clearPreferredService(context: Context) {
        if (NdefEmulation(context.applicationContext).currentEmulatedNdefData != null) return
        val activity = preferredActivity ?: (context as? Activity) ?: return
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        try {
            CardEmulation.getInstance(adapter).unsetPreferredService(activity)
            Log.d(TAG, "HCE preferred service cleared")
        } catch (_: Exception) {
        }
        if (preferredActivity == activity) preferredActivity = null
    }

    fun handle(context: Context, data: Uri): Boolean {
        if (data.scheme != "payto-wallet") return false
        val app = context.applicationContext
        val emulation = NdefEmulation(app)
        when (data.host) {
            "nfc" -> {
                val uri = data.getQueryParameter("uri") ?: return true
                Log.i(TAG, "start HCE uri=$uri")
                emulation.currentEmulatedNdefData = UriNdefData(uri)
                (context as? Activity)?.let(::syncPreferredService)
            }
            "nfc-stop" -> stop(context)
            else -> return false
        }
        return true
    }

    fun handleIntent(context: Context, intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return handle(context, data)
    }
}
