package com.kioskbrowser.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

data class KioskEvent(val name: String, val data: JSONObject = JSONObject())

/** App-wide event bus: events go to the web page (JS), MQTT and the remote admin. */
object EventHub {
    private val _events = MutableSharedFlow<KioskEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<KioskEvent> = _events

    fun emit(name: String, data: JSONObject = JSONObject()) {
        _events.tryEmit(KioskEvent(name, data))
    }

    fun emit(name: String, vararg pairs: Pair<String, Any?>) {
        val o = JSONObject()
        pairs.forEach { (k, v) -> o.put(k, v) }
        emit(name, o)
    }
}

/** Volatile runtime state shared between activity, service and servers. */
object KioskState {
    @Volatile var currentUrl: String = ""
    @Volatile var currentTitle: String = ""
    @Volatile var screensaverActive = false
    @Volatile var softScreenOff = false
    @Volatile var lastMotionTime = 0L
    @Volatile var lastSoundLevelDb = 0.0
    @Volatile var lastSoundTime = 0L
    @Volatile var kioskSuspended = false
    @Volatile var settingsOpen = false
    @Volatile var currentBrightness = -1
    val startTime = System.currentTimeMillis()

    fun motionRecently(windowMs: Long = 30_000) = System.currentTimeMillis() - lastMotionTime < windowMs
}
