package com.kioskbrowser.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject

/**
 * MQTT integration.
 *   <prefix>/<deviceId>/state          device info JSON (retained, periodic)
 *   <prefix>/<deviceId>/availability   online/offline (LWT)
 *   <prefix>/<deviceId>/event/<name>   events (motion, screenOn, pageFinished, ...)
 *   <prefix>/<deviceId>/cmd/<command>  commands, payload: JSON object or plain value
 *   <prefix>/cmd/<command>             broadcast commands to all devices
 *   <prefix>/<deviceId>/result         command results
 * Optional Home Assistant MQTT discovery.
 */
class MqttManager(private val ctx: Context, private val scope: CoroutineScope) {
    private val prefs = Prefs(ctx)
    private var client: MqttAsyncClient? = null
    private val id = DeviceController.deviceId
    private val base get() = "${prefs.mqttPrefix}/$id"

    val isConnected get() = client?.isConnected == true

    fun start() {
        if (!prefs.mqttEnabled) return
        stop()
        try {
            val c = MqttAsyncClient(prefs.mqttUrl, "kioskbrowser-$id", MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 30
                connectionTimeout = 15
                if (prefs.mqttUser.isNotBlank()) userName = prefs.mqttUser
                if (prefs.mqttPassword.isNotBlank()) password = prefs.mqttPassword.toCharArray()
                setWill("$base/availability", "offline".toByteArray(), 1, true)
            }
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    Log.i(KioskApp.TAG, "MQTT connected to $serverURI")
                    publish("$base/availability", "online", retained = true)
                    runCatching {
                        c.subscribe(arrayOf("$base/cmd/#", "${prefs.mqttPrefix}/cmd/#"), intArrayOf(1, 1))
                    }
                    if (prefs.mqttHaDiscovery) publishDiscovery()
                    publishState()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(KioskApp.TAG, "MQTT connection lost", cause)
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val cmd = topic.substringAfterLast("/cmd/", "")
                    if (cmd.isEmpty()) return
                    val payload = String(message.payload).trim()
                    val params = runCatching {
                        val o = JSONObject(payload)
                        o.keys().asSequence().associateWith { o.get(it).toString() }
                    }.getOrElse { if (payload.isEmpty()) emptyMap() else mapOf("value" to payload) }
                    scope.launch {
                        val result = CommandDispatcher.execute(ctx, cmd, params)
                        result.put("cmd", cmd)
                        publish("$base/result", result.toString())
                        publishState()
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(KioskApp.TAG, "MQTT connect failed", exception)
                }
            })
            client = c
        } catch (e: Exception) {
            Log.e(KioskApp.TAG, "MQTT start failed", e)
        }
    }

    fun stop() {
        val c = client ?: return
        client = null
        runCatching {
            if (c.isConnected) {
                c.publish("$base/availability", "offline".toByteArray(), 1, true)
                c.disconnect(1000).waitForCompletion(1500)
            }
        }
        runCatching { c.close(true) }
    }

    fun publish(topic: String, payload: String, retained: Boolean = false) {
        val c = client ?: return
        if (!c.isConnected) return
        runCatching { c.publish(topic, payload.toByteArray(), 0, retained) }
    }

    fun publishState() = publish("$base/state", DeviceController.deviceInfo().toString(), retained = true)

    fun publishEvent(e: KioskEvent) = publish("$base/event/${e.name}", e.data.toString())

    private fun publishDiscovery() {
        val device = JSONObject()
            .put("identifiers", JSONArray().put("kioskbrowser_$id"))
            .put("name", prefs.deviceName)
            .put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("manufacturer", "Kiosk Browser")
        val state = "$base/state"
        fun cfg(component: String, obj: String, name: String, extra: JSONObject.() -> Unit) {
            val o = JSONObject()
                .put("name", name)
                .put("unique_id", "kioskbrowser_${id}_$obj")
                .put("availability_topic", "$base/availability")
                .put("device", device)
            o.extra()
            publish("homeassistant/$component/kioskbrowser_$id/$obj/config", o.toString(), retained = true)
        }
        cfg("sensor", "battery", "Battery") {
            put("state_topic", state); put("value_template", "{{ value_json.batteryLevel }}")
            put("unit_of_measurement", "%"); put("device_class", "battery")
        }
        cfg("sensor", "url", "Current URL") {
            put("state_topic", state); put("value_template", "{{ value_json.currentUrl[:250] }}")
        }
        cfg("sensor", "sound", "Sound level") {
            put("state_topic", state); put("value_template", "{{ value_json.soundLevelDb }}")
            put("unit_of_measurement", "dB")
        }
        cfg("binary_sensor", "motion", "Motion") {
            put("state_topic", state); put("value_template", "{{ 'ON' if value_json.motionDetected else 'OFF' }}")
            put("device_class", "motion")
        }
        cfg("binary_sensor", "plugged", "Plugged in") {
            put("state_topic", state); put("value_template", "{{ 'ON' if value_json.plugged else 'OFF' }}")
            put("device_class", "plug")
        }
        cfg("switch", "screen", "Screen") {
            put("state_topic", state); put("value_template", "{{ 'ON' if value_json.screenOn else 'OFF' }}")
            put("command_topic", "$base/cmd/screen"); put("payload_on", "on"); put("payload_off", "off")
            put("state_on", "ON"); put("state_off", "OFF")
        }
        cfg("switch", "screensaver", "Screensaver") {
            put("state_topic", state); put("value_template", "{{ 'ON' if value_json.screensaverActive else 'OFF' }}")
            put("command_topic", "$base/cmd/screensaver"); put("payload_on", "on"); put("payload_off", "off")
            put("state_on", "ON"); put("state_off", "OFF")
        }
        cfg("number", "brightness", "Brightness") {
            put("state_topic", state); put("value_template", "{{ value_json.brightness }}")
            put("command_topic", "$base/cmd/setBrightness"); put("min", 0); put("max", 255)
        }
        cfg("number", "volume", "Volume") {
            put("state_topic", state); put("value_template", "{{ value_json.volume }}")
            put("command_topic", "$base/cmd/setVolume"); put("min", 0); put("max", 100)
        }
        cfg("text", "load_url", "Load URL") {
            put("command_topic", "$base/cmd/loadUrl")
            put("state_topic", state); put("value_template", "{{ value_json.currentUrl[:250] }}")
        }
        cfg("notify", "tts", "Speak") { put("command_topic", "$base/cmd/speak") }
        cfg("button", "reload", "Reload") { put("command_topic", "$base/cmd/reload") }
        cfg("button", "start_url", "Load start URL") { put("command_topic", "$base/cmd/loadStartUrl") }
        cfg("button", "restart", "Restart app") { put("command_topic", "$base/cmd/restartApp") }
        cfg("button", "foreground", "Bring to foreground") { put("command_topic", "$base/cmd/toForeground") }
    }
}
