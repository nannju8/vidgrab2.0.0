package com.vidgrab.app.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.data.BatchStatus
import com.vidgrab.app.data.DetectedVideo
import com.vidgrab.app.download.BatchSeriesManager
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URLEncoder

/** État d'un onglet. Les champs @Volatile sont lus par le thread réseau du WebView (jamais les états Compose). */
class TabState(val id: Int, val isBatchTab: Boolean = false) {
    @Volatile var currentUrl: String = ""
    @Volatile var currentTitle: String = ""
    @Volatile var lastTypedAt: Long = 0L
    @Volatile var lastLinkClickAt: Long = 0L
    @Volatile var lastClickedPlayer: String = ""
    @Volatile var winningPlayerLabel: String = ""
    @Volatile var lastSubframeAt: Long = 0L
    @Volatile var lastSubframeChallengeAt: Long = 0L
    @Volatile var lastSubframeHttpError: Int = 0
    @Volatile var lastSubframeHttpErrorAt: Long = 0L
    @Volatile var iframeSnapshot: IframeSnapshot = IframeSnapshot()

    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var desktop by mutableStateOf(false)
    var preview by mutableStateOf<ImageBitmap?>(null)
    var customView by mutableStateOf<View?>(null)
    var customCallback: WebChromeClient.CustomViewCallback? = null
    var scriptInjected = false

    val videos = mutableStateListOf<DetectedVideo>()

    var webView: WebView? = null
    var wrapper: MutableContextWrapper? = null

    private val main = Handler(Looper.getMainLooper())

    val sniffer: VideoSniffer = VideoSniffer { v, gen ->
        main.post {
            if (sniffer.isCurrent(gen) && videos.none { it.url == v.url }) {
                videos.add(v)
                BatchSeriesManager.onVideoDetected(id, v)
            }
        }
    }

    val isHome: Boolean get() = url.isBlank()

    fun resetIframeTracking() {
        lastSubframeAt = 0L
        lastSubframeChallengeAt = 0L
        lastSubframeHttpError = 0
        lastSubframeHttpErrorAt = 0L
        iframeSnapshot = IframeSnapshot()
    }
}

/** Pont JS → natif. @Keep obligatoire pour R8. */
@Keep
class JsBridge(private val tab: TabState) {
    @Keep
    @JavascriptInterface
    fun onVideo(src: String, title: String) {
        tab.sniffer.onJsVideo(src, tab.currentUrl, title.ifBlank { tab.currentTitle })
    }

    @Keep
    @JavascriptInterface
    fun linkClicked(href: String) {
        tab.lastLinkClickAt = System.currentTimeMillis()
    }

    @Keep
    @JavascriptInterface
    fun onPlayerClicked(label: String) {
        val clean = label.trim()
        if (clean.isNotBlank()) tab.lastClickedPlayer = clean
    }
}

object TabManager {
    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
    private const val BRIDGE = "VidGrab"

    private lateinit var appContext: Context
    val context: Context get() = appContext

    private var activityRef: WeakReference<Activity>? = null
    private val wrappers = mutableListOf<MutableContextWrapper>()
    private var nextId = 1
    private var mobileUa: String = ""

    val tabs = mutableStateListOf<TabState>()
    var currentIndex by mutableIntStateOf(0)

    val current: TabState
        get() {
            if (tabs.isEmpty()) tabs.add(TabState(nextId++))
            return tabs[currentIndex.coerceIn(0, tabs.lastIndex)]
        }

    fun init(app: Context) {
        appContext = app.applicationContext
        if (tabs.isEmpty()) tabs.add(TabState(nextId++))
        CookieManager.getInstance().setAcceptCookie(true)
    }

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        wrappers.forEach { it.baseContext = activity }
    }

    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
        wrappers.forEach { if (it.baseContext === activity) it.baseContext = appContext }
    }

    fun openNewTab(): TabState {
        val t = TabState(nextId++)
        tabs.add(t)
        currentIndex = tabs.lastIndex
        return t
    }

    fun openBackgroundTab(from: TabState): TabState {
        val t = TabState(nextId++, isBatchTab = true).apply {
            desktop = from.desktop
            lastClickedPlayer = from.lastClickedPlayer
            winningPlayerLabel = from.winningPlayerLabel.ifBlank { from.lastClickedPlayer }
        }
        tabs.add(t)
        return t
    }

    fun switchTo(tab: TabState) {
        val i = tabs.indexOf(tab)
        if (i >= 0) currentIndex = i
    }

    fun closeTab(tab: TabState) {
        BatchSeriesManager.stopIfTab(tab.id)
        tab.webView?.let { wv ->
            runCatching {
                wv.removeJavascriptInterface(BRIDGE)
                wv.stopLoading()
                wv.webChromeClient = null
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        tab.wrapper?.let { wrappers.remove(it) }
        tab.webView = null
        tab.wrapper = null
        val idx = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tabs.isEmpty()) tabs.add(TabState(nextId++))
        currentIndex = if (idx in 0 until currentIndex) currentIndex - 1 else currentIndex
        currentIndex = currentIndex.coerceIn(0, tabs.lastIndex)
    }

    fun normalize(input: String): String {
        val t = input.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (t.contains(' ') || !t.contains('.')) {
            return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        return "https://$t"
    }

    fun load(tab: TabState, input: String) {
        if (input.isBlank()) return
        val url = normalize(input)
        tab.lastTypedAt = System.currentTimeMillis()
        tab.resetIframeTracking()
        tab.url = url
        tab.currentUrl = url
        tab.loading = true
        tab.videos.clear()
        tab.sniffer.reset()
        webViewFor(tab).loadUrl(url)
    }

    fun goHome(tab: TabState) {
        BatchSeriesManager.stopIfTab(tab.id)
        tab.webView?.stopLoading()
        tab.webView?.onPause()
        tab.url = ""
        tab.currentUrl = ""
        tab.title = ""
        tab.loading = false
        tab.progress = 0f
        tab.winningPlayerLabel = ""
        tab.resetIframeTracking()
        tab.videos.clear()
        tab.sniffer.reset()
    }

    fun reload(tab: TabState) {
        tab.webView?.reload()
    }

    fun stop(tab: TabState) {
        tab.webView?.stopLoading()
        tab.loading = false
    }

    fun setDesktop(tab: TabState, on: Boolean) {
        tab.desktop = on
        val wv = tab.webView
        if (wv != null) applyUa(tab, wv)
        tab.sniffer.userAgent = if (on) DESKTOP_UA else mobileUa
        wv?.reload()
    }

    fun clearAll() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { t ->
            t.webView?.apply { clearCache(true); clearHistory(); clearFormData() }
        }
        AppPrefs.clearHistory()
    }

    fun capturePreview(tab: TabState) {
        val wv = tab.webView ?: return
        if (wv.width <= 0 || wv.height <= 0 || tab.isHome) return
        runCatching {
            val scale = 0.4f
            val bmp = Bitmap.createBitmap((wv.width * scale).toInt(), (wv.height * scale).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            wv.draw(c)
            tab.preview = bmp.asImageBitmap()
        }
    }

    private fun applyUa(tab: TabState, wv: WebView) {
        val s = wv.settings
        s.userAgentString = if (tab.desktop) DESKTOP_UA else mobileUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = tab.desktop
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webViewFor(tab: TabState): WebView {
        tab.webView?.let { return it }
        val wrapper = MutableContextWrapper(activityRef?.get() ?: appContext)
        wrappers.add(wrapper)
        tab.wrapper = wrapper
        val wv = WebView(wrapper)
        tab.webView = wv

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = false
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.allowFileAccess = false
        s.useWideViewPort = true
        if (mobileUa.isBlank()) mobileUa = s.userAgentString.replace("; wv", "")
        applyUa(tab, wv)
        tab.sniffer.userAgent = if (tab.desktop) DESKTOP_UA else mobileUa

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(JsBridge(tab), BRIDGE)
        IframeObserver.attach(wv, tab)
        wv.webViewClient = buildClient(tab)
        wv.webChromeClient = buildChrome(tab)
        wv.setDownloadListener { url, _, _, mime, _ ->
            if (mime.startsWith("video") || url.contains(".mp4") || url.contains(".m3u8")) {
                tab.sniffer.onJsVideo(url, tab.currentUrl, tab.currentTitle)
            }
        }
        return wv
    }

    private fun buildClient(tab: TabState) = object : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            val host = uri.host
            val url = uri.toString()
            if (!request.isForMainFrame) {
                if (AdBlocker.isAdHost(host)) {
                    return WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                val now = System.currentTimeMillis()
                tab.lastSubframeAt = now
                if (url.contains("challenges.cloudflare.com", true) ||
                    url.contains("turnstile", true) ||
                    url.contains("/cdn-cgi/challenge-platform", true)
                ) {
                    tab.lastSubframeChallengeAt = now
                }
            }
            val headers = HashMap<String, String>(request.requestHeaders)
            val cookie: String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
            tab.sniffer.onRequest(url, headers, tab.currentUrl, tab.currentTitle)
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return true
            val host = uri.host
            if (AdBlocker.isAdHost(host)) return true

            val curUrl = tab.currentUrl
            if (curUrl.isBlank()) return false
            val curHost = android.net.Uri.parse(curUrl).host
            val now = System.currentTimeMillis()
            return when {
                AdBlocker.rootDomain(host) == AdBlocker.rootDomain(curHost) -> false
                AdBlocker.isSearchEngine(curHost) -> false
                now - tab.lastTypedAt < 5_000L -> false
                now - tab.lastLinkClickAt < 3_000L -> false
                else -> true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.currentUrl = url
            tab.loading = true
            tab.progress = 0.05f
            tab.scriptInjected = false
            tab.resetIframeTracking()
            tab.videos.clear()
            tab.sniffer.reset()
            tab.canGoBack = view.canGoBack()
            val batch = BatchSeriesManager.state.value
            if (batch.config?.tabId != tab.id || batch.status == BatchStatus.IDLE) {
                tab.lastClickedPlayer = ""
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.loading = false
            tab.progress = 1f
            tab.canGoBack = view.canGoBack()
            val t = view.title.orEmpty()
            tab.title = t
            tab.currentTitle = t
            view.evaluateJavascript(AdBlocker.INJECTED_JS, null)
            view.evaluateJavascript(PageAutomator.PLAYER_TRACKER_JS, null)
            if (url != "about:blank") {
                if (!tab.isBatchTab) AppPrefs.addHistory(t, url)
                BatchSeriesManager.onPageFinished(tab.id, url)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            val code = errorResponse.statusCode
            if (request.isForMainFrame) {
                BatchSeriesManager.onHttpError(tab.id, code)
            } else if (!AdBlocker.isAdHost(request.url.host)) {
                val path = request.url.path.orEmpty().lowercase()
                val accept = request.requestHeaders["Accept"].orEmpty().lowercase()
                val looksLikeDoc = accept.contains("text/html") ||
                    (!path.endsWith(".js") && !path.endsWith(".css") && !path.endsWith(".png") &&
                        !path.endsWith(".jpg") && !path.endsWith(".svg") && !path.endsWith(".woff2") &&
                        !path.endsWith(".ts") && !path.endsWith(".m4s"))
                if (looksLikeDoc && code in listOf(403, 404, 410, 429, 503)) {
                    tab.lastSubframeHttpError = code
                    tab.lastSubframeHttpErrorAt = System.currentTimeMillis()
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            tab.currentUrl = url
            tab.canGoBack = view.canGoBack()
        }
    }

    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun showJsDialog(view: WebView, message: String, withCancel: Boolean, result: JsResult, fallbackConfirm: Boolean): Boolean {
        val act = findActivity(view.context)
        var handled = false
        fun once(block: () -> Unit) {
            if (!handled) {
                handled = true
                runCatching(block)
            }
        }
        if (act == null || act.isFinishing || act.isDestroyed) {
            once { if (fallbackConfirm) result.confirm() else result.cancel() }
            return true
        }
        try {
            val b = AlertDialog.Builder(act)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> once { result.confirm() } }
                .setOnCancelListener { once { result.cancel() } }
            if (withCancel) b.setNegativeButton(android.R.string.cancel) { _, _ -> once { result.cancel() } }
            b.show()
        } catch (e: Exception) {
            once { if (fallbackConfirm) result.confirm() else result.cancel() }
        }
        return true
    }

    private fun buildChrome(tab: TabState) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress / 100f
            tab.loading = newProgress < 100
            if (newProgress >= 70 && !tab.scriptInjected) {
                tab.scriptInjected = true
                view.evaluateJavascript(AdBlocker.INJECTED_JS, null)
                view.evaluateJavascript(PageAutomator.PLAYER_TRACKER_JS, null)
            }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title.orEmpty()
            tab.currentTitle = title.orEmpty()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            showJsDialog(view, message.orEmpty(), false, result, fallbackConfirm = false)

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            showJsDialog(view, message.orEmpty(), true, result, fallbackConfirm = false)

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            showJsDialog(view, message.orEmpty(), true, result, fallbackConfirm = true)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            tab.customView = view
            tab.customCallback = callback
        }

        override fun onHideCustomView() {
            tab.customView = null
            tab.customCallback?.onCustomViewHidden()
            tab.customCallback = null
        }
    }
}
