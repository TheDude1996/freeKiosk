package com.kioskbrowser.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long running foreground service: remote admin server, MQTT, motion & sound detection,
 * sleep/wake schedule and the "bring to foreground" watchdog.
 */
class KioskService : LifecycleService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private var server: RemoteAdminServer? = null
    private var mqtt: MqttManager? = null
    private var motion: MotionDetector? = null
    private var sound: SoundDetector? = null
    private var restartJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        instance = this
        goForeground()
        startComponents()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)

        lifecycleScope.launch {
            EventHub.events.collect { e ->
                if (e.name != "pageStart") mqtt?.publishEvent(e)
                if (e.name in STATE_EVENTS) mqtt?.publishState()
            }
        }
        lifecycleScope.launch { schedulerLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_REFRESH) { goForeground(); restartComponents() }
        return START_STICKY
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        stopComponents()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground

    private fun has(perm: String) = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText())
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= 30) {
            if (prefs.motionDetection && has(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (prefs.soundDetection && has(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, n, type) }
            .onFailure { Log.e(KioskApp.TAG, "startForeground failed", it); runCatching { startForeground(NOTIF_ID, n) } }
    }

    private fun statusText(): String = buildString {
        append(getString(R.string.notif_text))
        if (prefs.remoteAdminEnabled) append(" · http://${DeviceController.ipAddress()}:${prefs.remoteAdminPort}")
    }

    // ------------------------------------------------------------------ components

    private fun startComponents() {
        if (prefs.remoteAdminEnabled) runCatching {
            server = RemoteAdminServer(this, prefs.remoteAdminPort) { motion }.also { it.start(5000, false) }
            Log.i(KioskApp.TAG, "Remote admin on port ${prefs.remoteAdminPort}")
        }.onFailure { Log.e(KioskApp.TAG, "Remote admin failed", it) }

        if (prefs.mqttEnabled) mqtt = MqttManager(this, lifecycleScope).also { it.start() }

        if (prefs.motionDetection && has(Manifest.permission.CAMERA)) {
            motion = MotionDetector(this, this) { onMotion() }.also { it.start() }
        }
        if (prefs.soundDetection && has(Manifest.permission.RECORD_AUDIO)) {
            sound = SoundDetector(prefs.soundThresholdDb) { db ->
                EventHub.emit("sound", "levelDb" to db)
            }.also { it.start() }
        }
    }

    private fun stopComponents() {
        runCatching { server?.stop() }; server = null
        runCatching { mqtt?.stop() }; mqtt = null
        runCatching { motion?.stop() }; motion = null
        runCatching { sound?.stop() }; sound = null
    }

    private fun restartComponents() {
        restartJob?.cancel()
        restartJob = lifecycleScope.launch {
            delay(800) // debounce bursts of setting changes
            withContext(Dispatchers.IO) { stopComponents() }
            goForeground()
            startComponents()
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null) return
        if (COMPONENT_PREFIXES.any { key.startsWith(it) }) restartComponents()
    }

    private fun onMotion() {
        KioskState.lastMotionTime = System.currentTimeMillis()
        EventHub.emit("motion")
        lifecycleScope.launch(Dispatchers.Main) {
            val a = MainActivity.instance
            if (a != null && DeviceController.isScreenInteractive) a.onMotion()
            else if (prefs.motionWakeScreen) {
                DeviceController.wakeScreen()
                DeviceController.bringToForeground()
                MainActivity.instance?.onMotion()
            }
        }
    }

    // ------------------------------------------------------------------ scheduler

    private suspend fun schedulerLoop() {
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        var lastMinute = ""
        var lastState = 0L
        while (lifecycleScope.isActive) {
            val minute = fmt.format(Date())
            if (minute != lastMinute) {
                lastMinute = minute
                if (normalize(prefs.sleepTime) == minute) {
                    CommandDispatcher.execute(this, "screenOff", emptyMap())
                    EventHub.emit("scheduledSleep")
                }
                if (normalize(prefs.wakeTime) == minute) {
                    CommandDispatcher.execute(this, "screenOn", emptyMap())
                    EventHub.emit("scheduledWake")
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastState > prefs.mqttStateIntervalSec * 1000L) {
                lastState = now
                mqtt?.publishState()
            }
            if (prefs.kioskMode && prefs.forceForeground && !KioskState.kioskSuspended && !KioskState.settingsOpen &&
                !MainActivity.inForeground && DeviceController.isScreenInteractive
            ) {
                withContext(Dispatchers.Main) { DeviceController.bringToForeground() }
            }
            delay(5000)
        }
    }

    private fun normalize(t: String): String {
        val parts = t.split(":")
        if (parts.size != 2) return ""
        val h = parts[0].trim().toIntOrNull() ?: return ""
        val m = parts[1].trim().toIntOrNull() ?: return ""
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    companion object {
        private const val CHANNEL = "kiosk"
        private const val NOTIF_ID = 1
        const val ACTION_REFRESH = "com.kioskbrowser.app.REFRESH"
        @Volatile var instance: KioskService? = null
        private val COMPONENT_PREFIXES = listOf("remote_admin", "mqtt", "motion", "sound", "device_name")
        private val STATE_EVENTS = setOf(
            "motion", "screenOn", "screenOff", "pageFinished", "onScreensaverStart", "onScreensaverStop", "sound"
        )

        fun refresh(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, KioskService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
