package com.kioskbrowser.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/** Device level helpers: info, power, brightness, device admin, TTS and sound. */
@SuppressLint("StaticFieldLeak")
object DeviceController {
    private lateinit var ctx: Context
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<Pair<String, String?>>()
    private var player: MediaPlayer? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    val dpm get() = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent get() = ComponentName(ctx, KioskAdminReceiver::class.java)
    val isDeviceOwner get() = dpm.isDeviceOwnerApp(ctx.packageName)
    val isAdminActive get() = dpm.isAdminActive(adminComponent)

    val deviceId: String
        @SuppressLint("HardwareIds") get() = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    val isScreenInteractive: Boolean
        get() = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    val isScreenOn get() = isScreenInteractive && !KioskState.softScreenOff

    fun battery(): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return (if (level >= 0 && scale > 0) level * 100 / scale else -1) to plugged
    }

    fun ipAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull() ?: ""

    fun isInLockTask(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    fun deviceInfo(): JSONObject {
        val prefs = Prefs(ctx)
        val (level, plugged) = battery()
        val mem = ActivityManager.MemoryInfo().also {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val stat = StatFs(Environment.getDataDirectory().path)
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return JSONObject().apply {
            put("deviceName", prefs.deviceName)
            put("deviceId", deviceId)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("appVersion", pkg.versionName)
            put("ip", ipAddress())
            put("batteryLevel", level)
            put("plugged", plugged)
            put("screenOn", isScreenOn)
            put("brightness", currentBrightness())
            put("currentUrl", KioskState.currentUrl)
            put("currentTitle", KioskState.currentTitle)
            put("startUrl", prefs.startUrl)
            put("screensaverActive", KioskState.screensaverActive)
            put("kioskMode", prefs.kioskMode && !KioskState.kioskSuspended)
            put("lockTaskActive", isInLockTask())
            put("deviceOwner", isDeviceOwner)
            put("deviceAdmin", isAdminActive)
            put("motionDetection", prefs.motionDetection)
            put("motionDetected", KioskState.motionRecently())
            put("lastMotion", KioskState.lastMotionTime)
            put("soundLevelDb", KioskState.lastSoundLevelDb)
            put("volume", volume())
            put("ramFreeMb", mem.availMem / 1048576)
            put("ramTotalMb", mem.totalMem / 1048576)
            put("storageFreeMb", stat.availableBytes / 1048576)
            put("storageTotalMb", stat.totalBytes / 1048576)
            put("appUptimeSec", (System.currentTimeMillis() - KioskState.startTime) / 1000)
            put("deviceUptimeSec", SystemClock.elapsedRealtime() / 1000)
            put("foreground", MainActivity.inForeground)
        }
    }

    fun currentBrightness(): Int {
        if (KioskState.currentBrightness >= 0) return KioskState.currentBrightness
        return runCatching {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
    }

    /** Writes system brightness if permitted (persists outside the app too). */
    fun setSystemBrightness(value: Int): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        return runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        }.isSuccess
    }

    @Suppress("DEPRECATION")
    fun wakeScreen() {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "KioskBrowser:wake"
        )
        wl.acquire(5000)
    }

    fun lockNow(): Boolean = if (isAdminActive) runCatching { dpm.lockNow(); true }.getOrDefault(false) else false

    fun bringToForeground() {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(i) }
    }

    fun restartApp() {
        KioskApp.scheduleRestart(ctx, 1500)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // --- Volume ---
    private val audio get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volume(stream: Int = AudioManager.STREAM_MUSIC): Int {
        val max = audio.getStreamMaxVolume(stream)
        return if (max > 0) audio.getStreamVolume(stream) * 100 / max else 0
    }

    fun setVolume(percent: Int, stream: Int = AudioManager.STREAM_MUSIC) {
        val max = audio.getStreamMaxVolume(stream)
        runCatching { audio.setStreamVolume(stream, (percent.coerceIn(0, 100) * max) / 100, 0) }
    }

    // --- Text to speech ---
    fun speak(text: String, language: String? = null) {
        if (tts == null) {
            tts = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) synchronized(pendingSpeech) {
                    pendingSpeech.forEach { (t, l) -> doSpeak(t, l) }
                    pendingSpeech.clear()
                }
            }
        }
        if (ttsReady) doSpeak(text, language) else synchronized(pendingSpeech) { pendingSpeech += text to language }
    }

    private fun doSpeak(text: String, language: String?) {
        val t = tts ?: return
        if (!language.isNullOrBlank()) t.language = Locale.forLanguageTag(language)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "kiosk-${System.nanoTime()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    // --- Sound playback ---
    fun playSound(url: String, loop: Boolean) {
        stopSound()
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(url)
                isLooping = loop
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
        }
    }

    fun stopSound() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
