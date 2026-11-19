package com.kioskbrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private var ref: WeakReference<MainActivity>? = null
        val instance: MainActivity? get() = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        @Volatile var inForeground = false
    }

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    lateinit var webView: WebView
        private set
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var screensaver: FrameLayout
    private lateinit var screensaverWeb: WebView
    private lateinit var clock: TextView
    private lateinit var blackout: View
    private lateinit var hotCorner: View

    private val handler = Handler(Looper.getMainLooper())
    private var cornerTaps = 0
    private var firstCornerTap = 0L
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastErrorUrl: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var dialogOpen = false
    val jsBindings = ConcurrentHashMap<String, String>()

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        KioskService.refresh(this)
    }

    private val screensaverRunnable = Runnable { startScreensaver() }
    private val idleReloadRunnable = Runnable { loadStartUrl() }
    private val errorReloadRunnable = Runnable { lastErrorUrl?.let { webView.loadUrl(it) } }
    private val intervalReloadRunnable = object : Runnable {
        override fun run() {
            if (!KioskState.screensaverActive) webView.reload()
            scheduleIntervalReload()
        }
    }
    private val clockRunnable = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            // slowly move the clock to avoid burn-in
            clock.translationX = ((System.currentTimeMillis() / 60000) % 7 - 3) * 20f
            clock.translationY = ((System.currentTimeMillis() / 60000) % 5 - 2) * 30f
            handler.postDelayed(this, 1000)
        }
    }
    private val foregroundGuard = Runnable { applySystemUi() }

    // ---------------------------------------------------------------- lifecycle

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ref = WeakReference(this)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        webView = findViewById(R.id.webview)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        screensaver = findViewById(R.id.screensaver)
        screensaverWeb = findViewById(R.id.screensaver_web)
        clock = findViewById(R.id.clock)
        blackout = findViewById(R.id.blackout)
        hotCorner = findViewById(R.id.hot_corner)

        setupWebView(webView, main = true)
        setupWebView(screensaverWeb, main = false)
        swipe.setOnRefreshListener { webView.reload() }
        // Only allow pull-to-refresh when the page is scrolled to the top
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }

        hotCorner.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) onCornerTap()
            false // let the touch pass through to the web page
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    KioskState.screensaverActive -> stopScreensaver()
                    webView.canGoBack() -> webView.goBack()
                    kioskActive() -> Unit // swallow
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        if (prefs.clearCacheOnStart) {
            webView.clearCache(true)
        }

        ContextCompat.startForegroundService(this, Intent(this, KioskService::class.java))
        requestNeededPermissions()
        registerNetworkCallback()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                EventHub.events.collect { dispatchEventToPage(it) }
            }
        }

        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else loadStartUrl()
        scheduleIntervalReload()
        EventHub.emit("onAppStart")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it.startsWith("http") }?.let { loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        KioskState.settingsOpen = false
        applyWindowSettings()
        applyKioskMode()
        resetIdleTimers()
        EventHub.emit("onResume")
    }

    override fun onPause() {
        inForeground = false
        EventHub.emit("onPause")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUi() else if (kioskActive()) handler.postDelayed(foregroundGuard, 300)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        handler.removeCallbacksAndMessages(null)
        if (ref?.get() === this) ref = null
        runCatching { webView.destroy(); screensaverWeb.destroy() }
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (KioskState.softScreenOff) screenOn()
        if (KioskState.screensaverActive) stopScreensaver()
        resetIdleTimers()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null) return
        if (key in Prefs.WEB_KEYS) handler.post {
            setupWebView(webView, main = true)
            applyWindowSettings()
            applyKioskMode()
        }
        if (key.startsWith("screensaver") || key == "reload_on_idle_sec") handler.post { resetIdleTimers() }
        if (key == "reload_interval_min") handler.post { scheduleIntervalReload() }
        if (key == "start_url") handler.post { loadStartUrl() }
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView(wv: WebView, main: Boolean) {
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = !prefs.autoplay
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(prefs.zoom)
            builtInZoomControls = prefs.zoom
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setGeolocationEnabled(prefs.allowGeolocation)
            textZoom = prefs.textZoom
            val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(this@MainActivity)
            userAgentString = when {
                prefs.userAgent.isNotBlank() -> prefs.userAgent
                prefs.desktopMode -> defaultUa
                    .replace(Regex("\\(Linux; Android [^)]*\\)"), "(X11; Linux x86_64)")
                    .replace(" Mobile", "")
                else -> null
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        WebView.setWebContentsDebuggingEnabled(prefs.webDebugging)
        wv.setBackgroundColor(android.graphics.Color.BLACK)

        if (main) {
            wv.removeJavascriptInterface("kiosk")
            if (prefs.jsInterface) wv.addJavascriptInterface(JsInterface(this), "kiosk")
            swipe.isEnabled = prefs.pullToRefresh
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme?.lowercase() ?: ""
                if (scheme !in setOf("http", "https", "file", "about", "data", "blob", "javascript")) {
                    if (!prefs.blockExternalApps) runCatching {
                        val i = if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, request.url)
                        startActivity(i)
                    }
                    return true
                }
                if (!UrlFilter.isAllowed(url, prefs.whitelist, prefs.blacklist)) {
                    Toast.makeText(this@MainActivity, getString(R.string.url_blocked, url), Toast.LENGTH_SHORT).show()
                    EventHub.emit("urlBlocked", "url" to url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!main) return
                if (prefs.showProgress) progress.visibility = View.VISIBLE
                KioskState.currentUrl = url
                EventHub.emit("pageStart", "url" to url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!main) return
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                KioskState.currentUrl = url
                KioskState.currentTitle = view.title ?: ""
                if (url != lastErrorUrl) lastErrorUrl = null
                EventHub.emit("pageFinished", "url" to url, "title" to view.title)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!main || !request.isForMainFrame) return
                val url = request.url.toString()
                lastErrorUrl = url
                EventHub.emit("onNetworkError", "url" to url, "error" to error.description?.toString())
                val retry = prefs.errorReloadSec
                val msg = if (retry > 0) getString(R.string.load_error, retry) else error.description.toString()
                view.loadDataWithBaseURL(
                    null,
                    "<html><body style='background:#000;color:#aaa;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>" +
                        "<div><h2>${android.text.Html.escapeHtml(msg)}</h2><p>${android.text.Html.escapeHtml(url)}</p><p>${android.text.Html.escapeHtml(error.description?.toString() ?: "")}</p></div></body></html>",
                    "text/html", "utf-8", url
                )
                handler.removeCallbacks(errorReloadRunnable)
                if (retry > 0) handler.postDelayed(errorReloadRunnable, retry * 1000L)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, h: SslErrorHandler, error: SslError) {
                if (prefs.ignoreSsl) h.proceed() else h.cancel()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                Log.w(KioskApp.TAG, "WebView renderer gone – recreating activity")
                EventHub.emit("onRendererCrash")
                recreate()
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (main) progress.progress = newProgress
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (!prefs.allowWebCamMic) { request.deny(); return@runOnUiThread }
                    val wanted = request.resources.filter {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                            it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    }
                    if (wanted.isEmpty()) request.deny() else request.grant(wanted.toTypedArray())
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, prefs.allowGeolocation, false)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
            }

            override fun onHideCustomView() = hideCustomView()

            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return runCatching { fileChooser.launch(params.createIntent()); true }.getOrElse { fileCallback = null; false }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (main) KioskState.currentTitle = title ?: ""
            }
        }

        if (main) wv.setDownloadListener { url, ua, contentDisposition, mime, _ ->
            if (!prefs.allowDownloads) return@setDownloadListener
            runCatching {
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                getSystemService(DownloadManager::class.java).enqueue(req)
                Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideCustomView() {
        customView?.let { fullscreenContainer.removeView(it) }
        fullscreenContainer.visibility = View.GONE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    fun loadUrl(url: String) {
        if (url.isBlank()) return
        val u = if (url.contains("://") || url.startsWith("about:") || url.startsWith("data:")) url else "https://$url"
        stopScreensaver()
        webView.loadUrl(u)
    }

    fun loadStartUrl() = loadUrl(prefs.startUrl)
    fun reload() { stopScreensaver(); webView.reload() }
    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun clearCache() { webView.clearCache(true) }
    fun clearCookies() { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
    fun clearWebStorage() { WebStorage.getInstance().deleteAllData() }
    fun evaluateJs(code: String) { webView.evaluateJavascript(code, null) }

    private fun dispatchEventToPage(e: KioskEvent) {
        if (!prefs.jsInterface || e.name == "pageStart") return
        val detail = JSONObject().put("event", e.name).put("data", e.data).toString()
        val bound = jsBindings[e.name]
        val js = buildString {
            append("try{window.dispatchEvent(new CustomEvent('kiosk',{detail:$detail}));}catch(x){}")
            if (bound != null) append("try{(function(event){$bound})($detail);}catch(x){console.error(x)}")
        }
        webView.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------------- window / display

    private fun applyWindowSettings() {
        if (prefs.keepScreenOn && !KioskState.softScreenOff) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = when (prefs.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "reverse_portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            "reverse_landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        applySystemUi()
        if (!KioskState.screensaverActive && !KioskState.softScreenOff) applyBrightness(prefs.brightness)
    }

    private fun applySystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (prefs.fullscreen || kioskActive()) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 0..255, or -1 for system default. */
    fun applyBrightness(value: Int) {
        val lp = window.attributes
        lp.screenBrightness = if (value < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else (value.coerceIn(0, 255) / 255f).coerceAtLeast(0.004f)
        window.attributes = lp
        KioskState.currentBrightness = value
    }

    // ---------------------------------------------------------------- kiosk mode

    fun kioskActive() = prefs.kioskMode && !KioskState.kioskSuspended

    fun applyKioskMode() {
        val dc = DeviceController
        if (dc.isDeviceOwner) runCatching {
            val admin = dc.adminComponent
            dc.dpm.setLockTaskPackages(admin, arrayOf(packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dc.dpm.setLockTaskFeatures(admin, android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            val active = kioskActive()
            dc.dpm.setStatusBarDisabled(admin, active && prefs.disableStatusBar)
            dc.dpm.setKeyguardDisabled(admin, active && prefs.disableKeyguard)
            if (active) {
                dc.dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
                }
                dc.dpm.addPersistentPreferredActivity(admin, filter, componentName)
            } else {
                dc.dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                dc.dpm.clearPackagePersistentPreferredActivities(admin, packageName)
            }
        }.onFailure { Log.w(KioskApp.TAG, "device owner policy failed", it) }

        if (kioskActive() && prefs.lockTask) {
            if (!dc.isInLockTask()) runCatching { startLockTask() }
        } else if (dc.isInLockTask()) {
            runCatching { stopLockTask() }
        }
        applySystemUi()
    }

    private fun onCornerTap() {
        val now = System.currentTimeMillis()
        if (now - firstCornerTap > 3000) { firstCornerTap = now; cornerTaps = 0 }
        cornerTaps++
        if (cornerTaps >= prefs.exitTaps) {
            cornerTaps = 0
            askPin { showKioskMenu() }
        }
    }

    fun askPin(onSuccess: () -> Unit) {
        val pin = prefs.settingsPin
        if (pin.isEmpty()) { onSuccess(); return }
        if (dialogOpen) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.enter_pin)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        dialogOpen = true
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_pin)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (input.text.toString() == pin) onSuccess()
                else {
                    Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
                    EventHub.emit("wrongPin")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { dialogOpen = false; applySystemUi() }
            .show()
    }

    private fun showKioskMenu() {
        val items = arrayOf(
            getString(R.string.menu_settings), getString(R.string.menu_reload), getString(R.string.menu_home),
            getString(R.string.menu_unlock), getString(R.string.menu_exit)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openSettings()
                    1 -> reload()
                    2 -> loadStartUrl()
                    3 -> { KioskState.kioskSuspended = true; applyKioskMode() }
                    4 -> exitApp()
                }
            }
            .setOnDismissListener { applySystemUi() }
            .show()
    }

    private fun openSettings() {
        KioskState.settingsOpen = true
        if (DeviceController.isInLockTask()) runCatching { stopLockTask() }
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    fun exitApp() {
        KioskState.kioskSuspended = true
        applyKioskMode()
        stopService(Intent(this, KioskService::class.java))
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------- idle, screensaver, screen

    private fun resetIdleTimers() {
        handler.removeCallbacks(screensaverRunnable)
        handler.removeCallbacks(idleReloadRunnable)
        if (prefs.screensaverTimeoutSec > 0) handler.postDelayed(screensaverRunnable, prefs.screensaverTimeoutSec * 1000L)
        if (prefs.reloadOnIdleSec > 0) handler.postDelayed(idleReloadRunnable, prefs.reloadOnIdleSec * 1000L)
    }

    private fun scheduleIntervalReload() {
        handler.removeCallbacks(intervalReloadRunnable)
        if (prefs.reloadIntervalMin > 0) handler.postDelayed(intervalReloadRunnable, prefs.reloadIntervalMin * 60_000L)
    }

    fun startScreensaver() {
        if (KioskState.screensaverActive) return
        KioskState.screensaverActive = true
        screensaver.visibility = View.VISIBLE
        val url = prefs.screensaverUrl
        if (url.isNotBlank()) {
            clock.visibility = View.GONE
            screensaverWeb.visibility = View.VISIBLE
            screensaverWeb.loadUrl(url)
        } else {
            screensaverWeb.visibility = View.GONE
            clock.visibility = View.VISIBLE
            handler.post(clockRunnable)
        }
        if (prefs.screensaverBrightness >= 0) applyBrightness(prefs.screensaverBrightness)
        EventHub.emit("onScreensaverStart")
    }

    fun stopScreensaver() {
        if (!KioskState.screensaverActive) return
        KioskState.screensaverActive = false
        handler.removeCallbacks(clockRunnable)
        screensaver.visibility = View.GONE
        screensaverWeb.loadUrl("about:blank")
        applyBrightness(prefs.brightness)
        resetIdleTimers()
        EventHub.emit("onScreensaverStop")
    }

    fun screenOff() {
        if (prefs.screenOffMode == "lock" && DeviceController.lockNow()) {
            EventHub.emit("screenOff")
            return
        }
        KioskState.softScreenOff = true
        blackout.visibility = View.VISIBLE
        applyBrightness(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        EventHub.emit("screenOff")
    }

    fun screenOn() {
        DeviceController.wakeScreen()
        val wasOff = KioskState.softScreenOff || !DeviceController.isScreenInteractive
        KioskState.softScreenOff = false
        blackout.visibility = View.GONE
        applyWindowSettings()
        resetIdleTimers()
        if (wasOff) EventHub.emit("screenOn")
    }

    /** Called (on the main thread) when the camera detects motion. */
    fun onMotion() {
        if (prefs.motionWakeScreen && (KioskState.softScreenOff || !DeviceController.isScreenInteractive)) screenOn()
        if (prefs.motionStopScreensaver && KioskState.screensaverActive) stopScreensaver()
        resetIdleTimers()
    }

    // ---------------------------------------------------------------- screenshot

    suspend fun screenshotPng(): ByteArray? {
        val v = window.decorView
        if (v.width == 0 || v.height == 0) return null
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            PixelCopy.request(window, bmp, { result -> cont.resume(result == PixelCopy.SUCCESS) }, handler)
        }
        if (!ok) return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    // ---------------------------------------------------------------- misc

    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        if (prefs.motionDetection || prefs.allowWebCamMic) wanted += Manifest.permission.CAMERA
        if (prefs.soundDetection || prefs.allowWebCamMic) wanted += Manifest.permission.RECORD_AUDIO
        if (prefs.allowGeolocation) wanted += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                EventHub.emit("networkConnected")
                if (prefs.reloadOnNetwork && lastErrorUrl != null) handler.postDelayed({ lastErrorUrl?.let { webView.loadUrl(it) } }, 1500)
            }

            override fun onLost(network: Network) {
                EventHub.emit("networkDisconnected")
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    fun canDrawOverlays() = Settings.canDrawOverlays(this)
}
