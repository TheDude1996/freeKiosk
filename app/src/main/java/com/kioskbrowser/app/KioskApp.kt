package com.kioskbrowser.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import kotlin.system.exitProcess

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        DeviceController.init(this)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught exception – scheduling restart", error)
            runCatching {
                if (Prefs(this).restartOnCrash) scheduleRestart(this, 3000)
            }
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(2)
        }
    }

    companion object {
        const val TAG = "KioskBrowser"

        fun scheduleRestart(ctx: Context, delayMs: Long) {
            val intent = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(
                ctx, 42, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pi)
        }
    }
}
