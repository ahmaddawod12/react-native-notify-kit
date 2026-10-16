package com.notifeeexample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.Arguments

// Diagnostic-only, shell DUMP permission protected. No Activity, service start,
// process bootstrap, FGS policy exemption or native cleanup authority.
class Issue82ReceiptReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != "com.notifeeexample.ISSUE82_SIGNED_RECEIPT") return
    val correlation = intent.getStringExtra("correlationId") ?: return
    val wire = intent.getStringExtra("wire") ?: return
    if (!correlation.matches(Regex("[a-zA-Z0-9_-]{1,80}")) ||
        wire.length !in 1..16000 || !wire.matches(Regex("[a-zA-Z0-9_-]+"))) return
    val react = (context.applicationContext as? ReactApplication)?.reactHost?.currentReactContext
    if (react == null) {
      Log.w("Issue82Receipt", "REJECTED_NO_LIVE_CONTEXT")
      return
    }
    val payload = Arguments.createMap().apply {
      putString("correlationId", correlation)
      putString("wire", wire)
    }
    react.emitDeviceEvent("Issue82SignedReceipt", payload)
    Log.i("Issue82Receipt", "DELIVERED correlation=" + correlation + " length=" + wire.length)
  }
}
