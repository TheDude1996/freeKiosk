package com.kioskbrowser.app

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Central command handling shared by the REST API, MQTT and the JavaScript interface.
 * Every command returns a JSON object with at least "status".
 */
object CommandDispatcher {

    val COMMANDS = listOf(
        "loadUrl(url)", "loadStartUrl", "reload", "goBack", "clearCache", "clearCookies", "clearWebStorage",
        "screenOn", "screenOff", "screen(value=on|off)", "startScreensaver", "stopScreensaver", "screensaver(value=on|off)",
        "setBrightness(value 0-255)", "setVolume(value 0-100)", "speak(text, lang)", "stopSpeaking",
        "playSound(url, loop)", "stopSound", "toast(text)", "runJs(code)", "toForeground",
        "restartApp", "exitApp", "lockKiosk", "unlockKiosk", "setSetting(key, value)", "getSetting(key)",
        "deviceInfo", "listCommands"
    )

    suspend fun execute(ctx: Context, cmd: String, p: Map<String, String>): JSONObject {
        val prefs = Prefs(ctx)
        val v = p["value"] ?: ""
        return try {
            when (cmd) {
                "loadUrl" -> onActivity { it.loadUrl(p["url"] ?: v) }
                "loadStartUrl" -> onActivity { it.loadStartUrl() }
                "reload" -> onActivity { it.reload() }
                "goBack" -> onActivity { it.goBack() }
                "clearCache" -> onActivity { it.clearCache() }
                "clearCookies" -> onActivity { it.clearCookies() }
                "clearWebStorage" -> onActivity { it.clearWebStorage() }
                "screenOn" -> screen(true)
                "screenOff" -> screen(false)
                "screen" -> screen(v.equals("on", true) || v == "1" || v.equals("true", true))
                "startScreensaver" -> onActivity { it.startScreensaver() }
                "stopScreensaver" -> onActivity { it.stopScreensaver() }
                "screensaver" -> onActivity {
                    if (v.equals("on", true) || v == "1" || v.equals("true", true)) it.startScreensaver() else it.stopScreensaver()
                }
                "setBrightness" -> {
                    val b = (p["brightness"] ?: v).toIntOrNull() ?: return err("value must be 0-255 or -1")
                    prefs.set("brightness", b.toString())
                    onActivity { it.applyBrightness(b) }
                    if (b >= 0) DeviceController.setSystemBrightness(b)
                    ok()
                }
                "setVolume" -> {
                    DeviceController.setVolume((p["level"] ?: v).toIntOrNull() ?: return err("value 0-100"))
                    ok()
                }
                "speak" -> { DeviceController.speak(p["text"] ?: v, p["lang"]); ok() }
                "stopSpeaking" -> { DeviceController.stopSpeaking(); ok() }
                "playSound" -> {
                    DeviceController.playSound(p["url"] ?: v, (p["loop"] ?: "false").toBoolean()); ok()
                }
                "stopSound" -> { DeviceController.stopSound(); ok() }
                "toast" -> {
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, p["text"] ?: v, Toast.LENGTH_LONG).show() }
                    ok()
                }
                "runJs" -> onActivity { it.evaluateJs(p["code"] ?: v) }
                "toForeground" -> { withContext(Dispatchers.Main) { DeviceController.bringToForeground() }; ok() }
                "restartApp" -> { withContext(Dispatchers.Main) { DeviceController.restartApp() }; ok() }
                "exitApp" -> onActivity { it.exitApp() }
                "lockKiosk" -> {
                    KioskState.kioskSuspended = false
                    prefs.set("kiosk_mode", "true")
                    onActivity { it.applyKioskMode() }
                }
                "unlockKiosk" -> {
                    KioskState.kioskSuspended = true
                    onActivity { it.applyKioskMode() }
                }
                "setSetting", "setStringSetting", "setBooleanSetting" -> {
                    val key = p["key"] ?: return err("missing key")
                    prefs.set(key, v)
                    ok().put("key", key).put("value", prefs.sp.all[key])
                }
                "getSetting" -> {
                    val key = p["key"] ?: return err("missing key")
                    if (key in Prefs.SECRET_KEYS) return err("secret setting")
                    ok().put("key", key).put("value", prefs.sp.all[key] ?: JSONObject.NULL)
                }
                "deviceInfo" -> ok().put("info", DeviceController.deviceInfo())
                "listCommands" -> ok().put("commands", org.json.JSONArray(COMMANDS))
                else -> err("unknown command '$cmd'")
            }
        } catch (e: Exception) {
            err(e.message ?: e.toString())
        }
    }

    private suspend fun screen(on: Boolean): JSONObject {
        if (on) {
            withContext(Dispatchers.Main) {
                DeviceController.wakeScreen()
                MainActivity.instance?.screenOn() ?: DeviceController.bringToForeground()
            }
        } else {
            withContext(Dispatchers.Main) {
                val a = MainActivity.instance
                if (a != null) a.screenOff() else DeviceController.lockNow()
            }
        }
        return ok()
    }

    /** Runs [block] on the main thread with the kiosk activity, starting it if necessary. */
    private suspend fun onActivity(block: (MainActivity) -> Unit): JSONObject {
        var a = MainActivity.instance
        if (a == null) {
            withContext(Dispatchers.Main) { DeviceController.bringToForeground() }
            for (i in 0 until 30) {
                delay(100)
                a = MainActivity.instance
                if (a != null) break
            }
        }
        val act = a ?: return err("kiosk activity not running")
        withContext(Dispatchers.Main) { block(act) }
        return ok()
    }

    fun ok(): JSONObject = JSONObject().put("status", "OK")
    fun err(msg: String): JSONObject = JSONObject().put("status", "Error").put("message", msg)
}
