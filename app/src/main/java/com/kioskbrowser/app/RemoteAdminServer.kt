package com.kioskbrowser.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * Embedded web server: admin UI on "/" and a password protected REST API under "/api".
 *   GET  /api/info
 *   GET  /api/cmd?cmd=loadUrl&url=...      (all commands of CommandDispatcher)
 *   GET  /api/settings     POST /api/settings  (JSON object, merged)
 *   GET  /api/screenshot   (PNG)     GET /api/camshot (JPEG)
 * Password via query parameter "password" or header "X-Password".
 */
class RemoteAdminServer(
    private val ctx: Context,
    port: Int,
    private val cameraProvider: () -> MotionDetector?
) : NanoHTTPD(port) {

    private val prefs = Prefs(ctx)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        if (uri == "/" || uri == "/index.html") return asset("admin.html", "text/html")

        val body = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) runCatching { session.parseBody(body) }
        val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }.toMutableMap()

        val configured = prefs.remoteAdminPassword
        if (configured.isEmpty()) return json(Response.Status.FORBIDDEN, CommandDispatcher.err("Set a remote admin password in the app settings first"))
        val given = params["password"] ?: session.headers["x-password"] ?: ""
        if (!MessageDigest.isEqual(given.toByteArray(), configured.toByteArray())) {
            return json(Response.Status.UNAUTHORIZED, CommandDispatcher.err("wrong password"))
        }
        params.remove("password")

        return try {
            when (uri) {
                "/api/info" -> json(Response.Status.OK, CommandDispatcher.ok().put("info", DeviceController.deviceInfo()))
                "/api/cmd" -> {
                    // JSON body parameters are merged too
                    body["postData"]?.let { pd -> runCatching { JSONObject(pd) }.getOrNull()?.let { o -> o.keys().forEach { params[it] = o.get(it).toString() } } }
                    val cmd = params["cmd"] ?: return json(Response.Status.BAD_REQUEST, CommandDispatcher.err("missing cmd"))
                    val result = runBlocking { CommandDispatcher.execute(ctx, cmd, params) }
                    json(Response.Status.OK, result)
                }
                "/api/settings" -> {
                    val pd = body["postData"]
                    if (pd != null) {
                        prefs.importJson(JSONObject(pd))
                        json(Response.Status.OK, CommandDispatcher.ok())
                    } else json(Response.Status.OK, CommandDispatcher.ok().put("settings", prefs.toJson(includeSecrets = false)))
                }
                "/api/screenshot" -> {
                    val png = runBlocking {
                        withContext(Dispatchers.Main) { MainActivity.instance?.screenshotPng() }
                    } ?: return json(Response.Status.SERVICE_UNAVAILABLE, CommandDispatcher.err("screenshot not available"))
                    binary("image/png", png)
                }
                "/api/camshot" -> {
                    val det = cameraProvider() ?: return json(Response.Status.SERVICE_UNAVAILABLE, CommandDispatcher.err("enable motion detection to use the camera"))
                    val jpg = runBlocking { det.capture() } ?: return json(Response.Status.SERVICE_UNAVAILABLE, CommandDispatcher.err("camera not ready"))
                    binary("image/jpeg", jpg)
                }
                else -> json(Response.Status.NOT_FOUND, CommandDispatcher.err("not found"))
            }
        } catch (e: Exception) {
            Log.w(KioskApp.TAG, "remote admin error", e)
            json(Response.Status.INTERNAL_ERROR, CommandDispatcher.err(e.message ?: e.toString()))
        }
    }

    private fun asset(name: String, mime: String): Response = runCatching {
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    }.getOrElse { newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "missing") }

    private fun binary(mime: String, data: ByteArray) = cors(
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(data), data.size.toLong()).apply {
            addHeader("Cache-Control", "no-store")
        }
    )

    private fun json(status: Response.Status, o: JSONObject) =
        cors(newFixedLengthResponse(status, "application/json", o.toString()))

    private fun cors(r: Response): Response = r.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "X-Password, Content-Type")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }
}
