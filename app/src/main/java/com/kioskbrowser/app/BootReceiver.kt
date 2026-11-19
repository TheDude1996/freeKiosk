package com.kioskbrowser.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = runCatching { Prefs(context) }.getOrNull() ?: return
        if (!prefs.startOnBoot) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, KioskService::class.java)) }
        // Launching an activity from the background works for device owners, home apps
        // and apps holding the "display over other apps" permission.
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
