package com.kioskbrowser.app

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONObject

/** Typed access to all settings. Every setting can also be changed remotely by key. */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun str(key: String, def: String = ""): String = when (val v = sp.all[key]) {
        null -> def
        else -> v.toString()
    }

    fun bool(key: String, def: Boolean = false): Boolean = when (val v = sp.all[key]) {
        is Boolean -> v
        is String -> v.equals("true", true)
        else -> def
    }

    fun int(key: String, def: Int): Int = when (val v = sp.all[key]) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: def
        else -> def
    }

    // Web
    val startUrl get() = str("start_url", DEFAULT_START_URL).ifBlank { DEFAULT_START_URL }
    val whitelist get() = str("url_whitelist")
    val blacklist get() = str("url_blacklist")
    val userAgent get() = str("user_agent")
    val desktopMode get() = bool("desktop_mode")
    val zoom get() = bool("enable_zoom")
    val textZoom get() = int("text_zoom", 100).coerceIn(30, 300)
    val autoplay get() = bool("autoplay_media", true)
    val allowWebCamMic get() = bool("allow_web_cam_mic")
    val allowGeolocation get() = bool("allow_geolocation")
    val allowDownloads get() = bool("allow_downloads")
    val blockExternalApps get() = bool("block_external_apps", true)
    val ignoreSsl get() = bool("ignore_ssl_errors")
    val jsInterface get() = bool("js_interface", true)
    val webDebugging get() = bool("web_debugging")
    val clearCacheOnStart get() = bool("clear_cache_on_start")

    // Navigation
    val pullToRefresh get() = bool("pull_to_refresh")
    val showProgress get() = bool("show_progress", true)
    val reloadOnIdleSec get() = int("reload_on_idle_sec", 0)
    val reloadIntervalMin get() = int("reload_interval_min", 0)
    val errorReloadSec get() = int("error_reload_sec", 15)
    val reloadOnNetwork get() = bool("reload_on_network", true)

    // Kiosk
    val kioskMode get() = bool("kiosk_mode")
    val settingsPin get() = str("settings_pin", "1234")
    val exitTaps get() = int("exit_taps", 5).coerceIn(2, 20)
    val lockTask get() = bool("lock_task", true)
    val forceForeground get() = bool("force_foreground")
    val disableStatusBar get() = bool("disable_status_bar", true)
    val disableKeyguard get() = bool("disable_keyguard")

    // Display
    val fullscreen get() = bool("fullscreen", true)
    val keepScreenOn get() = bool("keep_screen_on", true)
    val orientation get() = str("orientation", "auto")
    val brightness get() = int("brightness", -1)
    val screenOffMode get() = str("screen_off_mode", "dim")

    // Screensaver
    val screensaverTimeoutSec get() = int("screensaver_timeout_sec", 0)
    val screensaverUrl get() = str("screensaver_url")
    val screensaverBrightness get() = int("screensaver_brightness", -1)

    // Schedule
    val sleepTime get() = str("sleep_time").trim()
    val wakeTime get() = str("wake_time").trim()

    // Detection
    val motionDetection get() = bool("motion_detection")
    val motionCamera get() = str("motion_camera", "front")
    val motionSensitivity get() = int("motion_sensitivity", 50).coerceIn(1, 100)
    val motionWakeScreen get() = bool("motion_wake_screen", true)
    val motionStopScreensaver get() = bool("motion_stop_screensaver", true)
    val soundDetection get() = bool("sound_detection")
    val soundThresholdDb get() = int("sound_threshold_db", 70).coerceIn(30, 100)

    // Remote admin
    val remoteAdminEnabled get() = bool("remote_admin_enabled")
    val remoteAdminPort get() = int("remote_admin_port", 2323).coerceIn(1024, 65535)
    val remoteAdminPassword get() = str("remote_admin_password")

    // MQTT
    val mqttEnabled get() = bool("mqtt_enabled")
    val mqttUrl get() = str("mqtt_url", "tcp://192.168.1.10:1883")
    val mqttUser get() = str("mqtt_user")
    val mqttPassword get() = str("mqtt_password")
    val mqttPrefix get() = str("mqtt_prefix", "kiosk").trim('/').ifBlank { "kiosk" }
    val mqttHaDiscovery get() = bool("mqtt_ha_discovery", true)
    val mqttStateIntervalSec get() = int("mqtt_state_interval_sec", 60).coerceIn(10, 3600)

    // Device
    val deviceName get() = str("device_name", "Kiosk").ifBlank { "Kiosk" }
    val startOnBoot get() = bool("start_on_boot", true)
    val restartOnCrash get() = bool("restart_on_crash", true)

    /** Set a value coming from REST / MQTT / JS. Booleans are detected by known key or current type. */
    fun set(key: String, value: String) {
        val isBool = key in BOOL_KEYS || sp.all[key] is Boolean
        sp.edit().apply {
            if (isBool) putBoolean(key, value.equals("true", true) || value == "1" || value.equals("on", true))
            else putString(key, value)
        }.apply()
    }

    fun toJson(includeSecrets: Boolean = true): JSONObject {
        val o = JSONObject()
        sp.all.toSortedMap().forEach { (k, v) ->
            if (!includeSecrets && k in SECRET_KEYS) return@forEach
            o.put(k, v)
        }
        return o
    }

    fun importJson(json: JSONObject) {
        val e = sp.edit()
        json.keys().forEach { k ->
            when (val v = json.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                JSONObject.NULL -> e.remove(k)
                else -> if (k in BOOL_KEYS) e.putBoolean(k, v.toString().toBoolean()) else e.putString(k, v.toString())
            }
        }
        e.apply()
    }

    companion object {
        const val DEFAULT_START_URL = "file:///android_asset/welcome.html"
        val SECRET_KEYS = setOf("settings_pin", "remote_admin_password", "mqtt_password")
        val BOOL_KEYS = setOf(
            "desktop_mode", "enable_zoom", "autoplay_media", "allow_web_cam_mic", "allow_geolocation",
            "allow_downloads", "block_external_apps", "ignore_ssl_errors", "js_interface", "web_debugging",
            "clear_cache_on_start", "pull_to_refresh", "show_progress", "reload_on_network", "kiosk_mode",
            "lock_task", "force_foreground", "disable_status_bar", "disable_keyguard", "fullscreen",
            "keep_screen_on", "motion_detection", "motion_wake_screen", "motion_stop_screensaver",
            "sound_detection", "remote_admin_enabled", "mqtt_enabled", "mqtt_ha_discovery",
            "start_on_boot", "restart_on_crash"
        )
        /** Settings that require the WebView / window to be re-configured. */
        val WEB_KEYS = setOf(
            "user_agent", "desktop_mode", "enable_zoom", "text_zoom", "autoplay_media", "js_interface",
            "web_debugging", "pull_to_refresh", "show_progress", "fullscreen", "keep_screen_on",
            "orientation", "brightness", "kiosk_mode", "lock_task", "disable_status_bar", "disable_keyguard"
        )
    }
}
