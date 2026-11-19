package com.kioskbrowser.app

import android.webkit.JavascriptInterface
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Exposed to web pages as `window.kiosk`. Example:
 *   kiosk.getBatteryLevel(); kiosk.turnScreenOff(); kiosk.bind('motion', 'console.log(event)');
 * Events are also dispatched as `window.addEventListener('kiosk', e => e.detail.event ...)`.
 */
class JsInterface(private val activity: MainActivity) {
    private val prefs = Prefs(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun ui(block: () -> Unit) = activity.runOnUiThread(block)

    @JavascriptInterface fun getDeviceId(): String = DeviceController.deviceId
    @JavascriptInterface fun getDeviceName(): String = prefs.deviceName
    @JavascriptInterface fun getBatteryLevel(): Int = DeviceController.battery().first
    @JavascriptInterface fun isPlugged(): Boolean = DeviceController.battery().second
    @JavascriptInterface fun getIp4Address(): String = DeviceController.ipAddress()
    @JavascriptInterface fun getDeviceInfo(): String = DeviceController.deviceInfo().toString()
    @JavascriptInterface fun getCurrentUrl(): String = KioskState.currentUrl
    @JavascriptInterface fun getStartUrl(): String = prefs.startUrl
    @JavascriptInterface fun isScreenOn(): Boolean = DeviceController.isScreenOn
    @JavascriptInterface fun isInScreensaver(): Boolean = KioskState.screensaverActive
    @JavascriptInterface fun isMotionDetected(): Boolean = KioskState.motionRecently(5000)
    @JavascriptInterface fun getSoundLevel(): Double = KioskState.lastSoundLevelDb
    @JavascriptInterface fun getScreenBrightness(): Int = DeviceController.currentBrightness()
    @JavascriptInterface fun getVolume(): Int = DeviceController.volume()

    @JavascriptInterface fun setScreenBrightness(value: Int) = ui { activity.applyBrightness(value) }
    @JavascriptInterface fun setVolume(percent: Int) = DeviceController.setVolume(percent)
    @JavascriptInterface fun turnScreenOn() = ui { activity.screenOn() }
    @JavascriptInterface fun turnScreenOff() = ui { activity.screenOff() }
    @JavascriptInterface fun startScreensaver() = ui { activity.startScreensaver() }
    @JavascriptInterface fun stopScreensaver() = ui { activity.stopScreensaver() }
    @JavascriptInterface fun loadStartUrl() = ui { activity.loadStartUrl() }
    @JavascriptInterface fun loadUrl(url: String) = ui { activity.loadUrl(url) }
    @JavascriptInterface fun reload() = ui { activity.reload() }
    @JavascriptInterface fun clearCache() = ui { activity.clearCache() }
    @JavascriptInterface fun clearCookies() = ui { activity.clearCookies() }
    @JavascriptInterface fun showToast(text: String) = ui { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    @JavascriptInterface fun textToSpeech(text: String) = DeviceController.speak(text)
    @JavascriptInterface fun textToSpeech(text: String, lang: String) = DeviceController.speak(text, lang)
    @JavascriptInterface fun stopTextToSpeech() = DeviceController.stopSpeaking()
    @JavascriptInterface fun playSound(url: String, loop: Boolean) = DeviceController.playSound(url, loop)
    @JavascriptInterface fun stopSound() = DeviceController.stopSound()
    @JavascriptInterface fun bringToForeground() = ui { DeviceController.bringToForeground() }
    @JavascriptInterface fun restartApp() = ui { DeviceController.restartApp() }

    /** Settings: secret values (PIN, passwords) are never readable or writable from JS. */
    @JavascriptInterface fun getStringSetting(key: String): String =
        if (key in Prefs.SECRET_KEYS) "" else prefs.str(key)
    @JavascriptInterface fun getBooleanSetting(key: String): Boolean = prefs.bool(key)
    @JavascriptInterface fun setStringSetting(key: String, value: String) { if (key !in Prefs.SECRET_KEYS) prefs.set(key, value) }
    @JavascriptInterface fun setBooleanSetting(key: String, value: Boolean) { if (key !in Prefs.SECRET_KEYS) prefs.set(key, value.toString()) }

    /** Bind JS code to an event (motion, screenOn, screenOff, onScreensaverStart, sound, ...). `event` holds details. */
    @JavascriptInterface fun bind(event: String, code: String) { activity.jsBindings[event] = code }
    @JavascriptInterface fun unbind(event: String) { activity.jsBindings.remove(event) }

    /** Generic access to every command of the REST API, result arrives via callback(resultJson). */
    @JavascriptInterface fun exec(cmd: String, paramsJson: String, callback: String) {
        scope.launch {
            val params = runCatching {
                val o = JSONObject(paramsJson.ifBlank { "{}" })
                o.keys().asSequence().associateWith { o.get(it).toString() }
            }.getOrDefault(emptyMap())
            val result = CommandDispatcher.execute(activity, cmd, params)
            if (callback.matches(Regex("[A-Za-z_$][\\w.$]*"))) ui {
                activity.webView.evaluateJavascript("$callback($result)", null)
            }
        }
    }
}
