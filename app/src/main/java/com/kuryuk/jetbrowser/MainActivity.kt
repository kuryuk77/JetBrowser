package com.kuryuk.jetbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.SearchManager
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var urlEdit: EditText
    private lateinit var tabCount: TextView
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var dialGrid: GridLayout
    private lateinit var fullHolder: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var remote: RemoteServer

    private val tabs = mutableListOf<JetWebView>()
    private var current: JetWebView? = null
    private var showingHome = true
    private var homeViaButton = false
    private var cursorMode = false
    private var remoteConnected = false
    private var isTv = false
    private var bypassCursor = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var activeDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())

    private var downTime = 0L
    private var pressed = false
    private var longDown = false
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f

    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this).replace("; wv", "") }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val density get() = resources.displayMetrics.density

    // ------------------------------------------------------------------ yaşam döngüsü

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val ui = getSystemService(UI_MODE_SERVICE) as UiModeManager
        isTv = ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        cursorMode = store.cursorDefault(isTv)
        buildUi()
        setContentView(root)
        remote = RemoteServer(this, ::onRemote, ::onRemoteClients)
        remote.fileListener = { ev, name, file ->
            when (ev) {
                0 -> toast("Telefondan dosya alınıyor: $name")
                1 -> if (file != null) onFileReceived(file)
                else -> toast("Dosya alınamadı: $name")
            }
        }
        remote.start()
        restoreTabs()
        if (!handleIntent(intent)) showHome(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private var pendingApk: File? = null

    override fun onResume() {
        super.onResume()
        val f = pendingApk
        if (f != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            pendingApk = null
            installApk(f)
        }
    }

    // ------------------------------------------------------------------ telefondan gelen dosyalar

    private fun onFileReceived(f: File) {
        if (f.name.lowercase().endsWith(".apk")) { installApk(f); return }
        show(builder().setTitle("Dosya alındı").setMessage(f.name)
            .setPositiveButton("Aç") { _, _ -> openReceived(f) }
            .setNegativeButton("Tamam", null))
    }

    private fun openReceived(f: File) {
        if (f.name.lowercase().endsWith(".apk")) { installApk(f); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileShare.uri(this, f), FileShare.mime(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast("Bu dosyayı açacak uygulama yok") }
    }

    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingApk = f
            toast("JetBrowser'a uygulama yükleme izni verip geri dönün")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            } catch (_: Exception) {
                toast("TV ayarlarında JetBrowser'a 'bilinmeyen kaynak' izni verin")
            }
        }
        try {
            @Suppress("DEPRECATION")
            startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(FileShare.uri(this, f), FileShare.mime(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileShare.uri(this, f), FileShare.mime(f.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { toast("Kurulum ekranı açılamadı") }
        }
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        remote.stop()
        tabs.forEach { it.destroy() }
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?): Boolean {
        val url = when (i?.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)
            else -> null
        } ?: return false
        val cur = current
        if (cur == null || cur.url != null || cur.pending != null) newTab(false)
        go(url)
        return true
    }

    private fun saveTabs() {
        val arr = JSONArray()
        tabs.filter { !it.incognito }.forEach { t -> (t.url ?: t.pending)?.let { arr.put(it) } }
        getSharedPreferences("jet", MODE_PRIVATE).edit()
            .putString("tabs", arr.toString())
            .putInt("tabIndex", tabs.filter { !it.incognito }.indexOf(current))
            .apply()
    }

    private fun restoreTabs() {
        val p = getSharedPreferences("jet", MODE_PRIVATE)
        try {
            val arr = JSONArray(p.getString("tabs", "[]"))
            for (i in 0 until arr.length()) newTab(false).pending = arr.getString(i)
        } catch (_: Exception) { }
        val idx = p.getInt("tabIndex", -1)
        if (idx in tabs.indices) current = tabs[idx]
        updateTabCount()
    }

    // ------------------------------------------------------------------ arayüz

    private fun buildUi() {
        root = FrameLayout(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(0xFF121212.toInt())
        }
        bar.addView(iconBtn(R.drawable.ic_home) { showHome(true) })
        bar.addView(iconBtn(R.drawable.ic_back) { handleBack() })
        bar.addView(iconBtn(R.drawable.ic_refresh) { current?.reload() })

        val urlBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.url_bg)
            setPadding(dp(14), 0, dp(2), 0)
        }
        urlEdit = EditText(this).apply {
            hint = "Ara veya adres yaz"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = null
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9E9E9E.toInt())
            textSize = 16f
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_GO || enter) { go(text.toString()); true } else false
            }
        }
        urlBox.addView(urlEdit, LinearLayout.LayoutParams(0, dp(44), 1f))
        urlBox.addView(iconBtn(R.drawable.ic_mic) { voice() })
        bar.addView(urlBox, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })

        bar.addView(iconBtn(R.drawable.ic_add) { newTab(false); showHome(false) })
        tabCount = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                setStroke(dp(2), Color.WHITE); cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { tabsDialog() }
        }
        val tabWrap = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.btn_bg)
            isFocusable = true
            isClickable = true
            setOnClickListener { tabsDialog() }
            addView(tabCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        tabCount.isFocusable = false
        tabCount.isClickable = false
        bar.addView(tabWrap, LinearLayout.LayoutParams(dp(46), dp(46)))
        bar.addView(iconBtn(R.drawable.ic_more) { showMenu() })
        column.addView(bar, LinearLayout.LayoutParams(-1, -2))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(0xFFE53935.toInt())
            visibility = View.INVISIBLE
        }
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        content = FrameLayout(this)
        buildHome()
        content.addView(homeView, FrameLayout.LayoutParams(-1, -1))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        fullHolder = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isFocusable = true
        }
        cursor = CursorView(this).apply { visibility = View.GONE }

        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.addView(fullHolder, FrameLayout.LayoutParams(-1, -1))
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> updateCursorVisibility() }
    }

    private fun iconBtn(res: Int, onClick: () -> Unit): View = ImageButton(this).apply {
        setImageResource(res)
        setBackgroundResource(R.drawable.btn_bg)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
    }

    private fun buildHome() {
        homeView = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(28), dp(12), dp(24))
        }
        val logo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        logo.addView(ImageView(this).apply { setImageResource(R.drawable.ic_jet) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        logo.addView(TextView(this).apply {
            text = "JetBrowser"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        })
        col.addView(logo)

        dialGrid = GridLayout(this)
        col.addView(dialGrid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })

        val actions = GridLayout(this).apply { columnCount = 4 }
        val items = listOf(
            Triple(R.drawable.ic_mouse, "Kumanda") { remoteTile() },
            Triple(R.drawable.ic_star, "Yer imleri") { bookmarksDialog() },
            Triple(R.drawable.ic_history, "Geçmiş") { historyDialog() },
            Triple(R.drawable.ic_download, "İndirilenler") { downloadsDialog() },
            Triple(R.drawable.ic_incognito, "Gizli") { newTab(true); showHome(false); toast("Gizli sekme: geçmiş kaydedilmez") },
            Triple(R.drawable.ic_delete, "Temizle") { clearDataDialog() },
            Triple(R.drawable.ic_tune, "Özelleştir") { customizeDialog() },
            Triple(R.drawable.ic_settings, "Ayarlar") { settingsDialog() }
        )
        items.forEach { (ic, label, action) -> actions.addView(actionTile(ic, label, action)) }
        col.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        homeView.addView(col)
    }

    private fun actionTile(icon: Int, label: String, onClick: () -> Unit): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.btn_bg)
            isFocusable = true
            isClickable = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { onClick() }
        }
        val box = ImageView(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setStroke(dp(2), 0xFF555555.toInt()); cornerRadius = dp(12).toFloat()
            }
        }
        v.addView(box, LinearLayout.LayoutParams(dp(60), dp(60)))
        v.addView(labelView(label))
        v.layoutParams = GridLayout.LayoutParams().apply { width = dp(86); setMargins(dp(4), dp(4), dp(4), dp(4)) }
        return v
    }

    private fun labelView(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFFE0E0E0.toInt())
        textSize = 13f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, 0)
    }

    private fun refreshDial() {
        dialGrid.removeAllViews()
        val wDp = resources.displayMetrics.widthPixels / density
        dialGrid.columnCount = (wDp / 112).toInt().coerceIn(3, 8)
        store.speedDial.forEachIndexed { i, s ->
            val t = dialTile(iconFor(s.url), s.title) { go(s.url) }
            t.setOnLongClickListener { dialOptions(i); true }
            dialGrid.addView(t)
        }
        dialGrid.addView(dialTile(null, "Ekle", plus = true) { editDialDialog(-1, "", current?.url ?: "") })
    }

    private fun dialTile(icon: Bitmap?, title: String, plus: Boolean = false, onClick: () -> Unit): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.btn_bg)
            isFocusable = true
            isClickable = true
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { onClick() }
        }
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(18).toFloat() }
        }
        if (icon != null) {
            box.addView(ImageView(this).apply { setImageBitmap(icon) }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        } else {
            box.addView(TextView(this).apply {
                text = if (plus) "+" else title.trim().take(1).uppercase()
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (plus) Color.WHITE else 0xFFE53935.toInt())
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        v.addView(box, LinearLayout.LayoutParams(dp(76), dp(76)))
        v.addView(labelView(title))
        v.layoutParams = GridLayout.LayoutParams().apply { width = dp(100); setMargins(dp(3), dp(3), dp(3), dp(3)) }
        return v
    }

    private fun iconFor(url: String): Bitmap? {
        val host = Uri.parse(url).host ?: return null
        val bare = host.removePrefix("www.").removePrefix("m.")
        for (h in listOf(host, bare, "www.$bare", "m.$bare")) {
            val f = File(filesDir, "icons/$h.png")
            if (f.exists()) return BitmapFactory.decodeFile(f.path)
        }
        return null
    }

    private fun updateTabCount() {
        if (::tabCount.isInitialized) tabCount.text = tabs.size.toString()
    }

    // ------------------------------------------------------------------ sekmeler ve gezinme

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(incognito: Boolean): JetWebView {
        val w = JetWebView(this, incognito)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.noIme = remoteConnected
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = !store.popupBlock
            setSupportMultipleWindows(true)
            textZoom = store.textZoom
            if (incognito) cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = ua()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = Client(w)
        w.webChromeClient = Chrome(w)
        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        w.visibility = View.GONE
        tabs.add(w)
        content.addView(w, FrameLayout.LayoutParams(-1, -1))
        current = w
        updateTabCount()
        return w
    }

    private fun ua(): String = if (store.desktopMode) DESKTOP_UA else defaultUa

    private fun closeTab(t: JetWebView) {
        content.removeView(t)
        tabs.remove(t)
        t.destroy()
        if (current === t) {
            current = tabs.lastOrNull()
            val c = current
            if (c == null || (c.url == null && c.pending == null)) showHome(false) else showWeb()
        }
        updateTabCount()
    }

    private fun showWeb() {
        val c = current ?: return
        showingHome = false
        homeView.visibility = View.GONE
        tabs.forEach { it.visibility = if (it === c) View.VISIBLE else View.GONE }
        c.pending?.let { c.pending = null; c.loadUrl(it) }
        if (!urlEdit.hasFocus()) urlEdit.setText(c.url ?: "")
        c.requestFocus()
        updateCursorVisibility()
    }

    private fun showHome(viaButton: Boolean) {
        showingHome = true
        homeViaButton = viaButton
        tabs.forEach { it.visibility = View.GONE }
        homeView.visibility = View.VISIBLE
        progress.visibility = View.INVISIBLE
        refreshDial()
        urlEdit.setText("")
        homeView.scrollTo(0, 0)
        if (isTv && !remoteConnected) dialGrid.getChildAt(0)?.requestFocus()
        updateCursorVisibility()
    }

    private fun go(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = toUrl(s)
        hideKeyboard()
        urlEdit.clearFocus()
        val t = current ?: newTab(false)
        t.pending = null
        showWeb()
        t.loadUrl(url)
    }

    private fun toUrl(s: String): String {
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*").matches(s) && (s.contains("://") || s.startsWith("about:"))) return s
        if (!s.contains(' ') && (s.startsWith("localhost") || Regex("^[^.\\s]+(\\.[^.\\s]+)+(/.*)?$").matches(s)))
            return "https://$s"
        return Store.ENGINE_URLS[store.searchEngine.coerceIn(0, 3)] + URLEncoder.encode(s, "UTF-8")
    }

    private fun handleBack(): Boolean {
        if (customView != null) { exitFullscreen(); return true }
        if (urlEdit.hasFocus()) {
            urlEdit.clearFocus(); hideKeyboard()
            if (!showingHome) current?.requestFocus()
            return true
        }
        val t = current
        if (!showingHome && t != null) {
            if (t.canGoBack()) { t.goBack(); return true }
            showHome(false); return true
        }
        if (showingHome && homeViaButton && t != null && (t.url != null || t.pending != null)) {
            showWeb(); return true
        }
        return false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBack()) moveTaskToBack(true)
    }

    // ------------------------------------------------------------------ WebView istemcileri

    private inner class Client(val tab: JetWebView) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUrl(view, request.url.toString(), request.hasGesture() || !request.isForMainFrame)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleUrl(view, url, true)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (store.adBlock && AdBlock.isAd(request.url))
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (tab === current && !showingHome) {
                if (!urlEdit.hasFocus()) urlEdit.setText(url ?: "")
                progress.visibility = View.VISIBLE
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (tab === current) progress.visibility = View.INVISIBLE
            if (!tab.incognito && url != null) store.addHistory(view.title ?: url, url)
        }
    }

    private fun handleUrl(view: WebView, url: String, gesture: Boolean): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            if (store.adBlock && AdBlock.isAd(uri)) { toast("Reklam yönlendirmesi engellendi"); return true }
            return false
        }
        if (scheme in listOf("about", "data", "javascript", "blob", "file")) return false
        if (!gesture) return true
        try {
            val intent = if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fb = intent.getStringExtra("browser_fallback_url")
                if (fb != null) view.loadUrl(fb) else toast("Bunu açacak uygulama yok")
            }
        } catch (_: Exception) { toast("Açılamadı") }
        return true
    }

    private inner class Chrome(val tab: JetWebView) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            if (tab === current && !showingHome) {
                progress.progress = p
                progress.visibility = if (p >= 100) View.INVISIBLE else View.VISIBLE
            }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            val host = Uri.parse(view.url ?: return).host ?: return
            if (icon == null) return
            Thread {
                try {
                    val dir = File(filesDir, "icons"); dir.mkdirs()
                    FileOutputStream(File(dir, "$host.png")).use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (_: Exception) { }
            }.start()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val hit = view.hitTestResult
            val link = if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) hit.extra else null
            if (store.popupBlock) {
                if (link != null && isUserGesture && !AdBlock.isAd(Uri.parse(link))) {
                    newTab(tab.incognito); go(link)
                } else toast("Açılır pencere engellendi")
                return false
            }
            val t = newTab(tab.incognito)
            showWeb()
            (resultMsg.obj as WebView.WebViewTransport).webView = t
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.find { it === window }?.let { closeTab(it) }
        }

        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customCallback = callback
            fullHolder.addView(view, FrameLayout.LayoutParams(-1, -1))
            fullHolder.visibility = View.VISIBLE
            fullHolder.requestFocus()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            immersive(true)
            cursor.bringToFront()
            updateCursorVisibility()
        }

        override fun onHideCustomView() = exitFullscreen()
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        customView = null
        fullHolder.removeView(v)
        fullHolder.visibility = View.GONE
        val cb = customCallback
        customCallback = null
        cb?.onCustomViewHidden()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        immersive(false)
        current?.requestFocus()
        updateCursorVisibility()
    }

    @Suppress("DEPRECATION")
    private fun immersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        else 0
    }

    // ------------------------------------------------------------------ imleç (TV kumandası + telefon)

    private fun updateCursorVisibility() {
        if (!::cursor.isInitialized) return
        val show = remoteConnected ||
            (cursorMode && (customView != null || (!showingHome && current != null)) && !bar.hasFocus())
        cursor.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { cursor.centerIfUnset(); cursor.invalidate() }
    }

    private fun cursorKeysActive(): Boolean =
        cursorMode && !bypassCursor && !bar.hasFocus() &&
            (customView != null || (!showingHome && current != null))

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (k == KeyEvent.KEYCODE_MENU) { if (!down) showMenu(); return true }
        if (cursorKeysActive()) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) keyMove(k, e.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (down && e.repeatCount == 0) touchAt(MotionEvent.ACTION_DOWN)
                    else if (!down && pressed) { touchAt(MotionEvent.ACTION_UP); afterClick() }
                    return true
                }
            }
        }
        if (cursorMode && down && k == KeyEvent.KEYCODE_DPAD_DOWN && bar.hasFocus() && !showingHome && current != null) {
            current?.requestFocus(); return true
        }
        val web = current
        if (down && web != null && !showingHome) {
            when (k) {
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { web.pageDown(false); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { web.pageUp(false); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun keyMove(k: Int, repeat: Int) {
        cursor.centerIfUnset()
        val base = dp(5) * (store.cursorSpeed + 1) / 2f
        val step = base * (1 + min(repeat, 24) / 4f)
        var dx = 0f
        var dy = 0f
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
        }
        val web = if (customView == null) current else null
        if (web != null) {
            val top = contentTop()
            val scroll = root.height / 6
            val edge = dp(4)
            if (dy < 0 && cursor.cy <= top + edge) {
                if (web.canScrollVertically(-1)) web.scrollBy(0, -scroll)
                else { bar.getChildAt(0).requestFocus() }
                return
            }
            if (dy > 0 && cursor.cy >= root.height - edge - 1) { web.scrollBy(0, scroll); return }
            if (dx < 0 && cursor.cx <= edge) { if (web.canScrollHorizontally(-1)) web.scrollBy(-scroll, 0); return }
            if (dx > 0 && cursor.cx >= root.width - edge - 1) { if (web.canScrollHorizontally(1)) web.scrollBy(scroll, 0); return }
        }
        cursor.moveBy(dx, dy)
    }

    private fun contentTop(): Int {
        val a = IntArray(2)
        val b = IntArray(2)
        content.getLocationInWindow(a)
        root.getLocationInWindow(b)
        return a[1] - b[1]
    }

    private fun touchAt(action: Int, x: Float = cursor.cx, y: Float = cursor.cy) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) { downTime = now; pressed = true }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!pressed) return
            pressed = false
        }
        val ev = MotionEvent.obtain(downTime, now, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Tıklamadan sonra bir yazı alanı seçildiyse telefona "klavyeyi aç" de; TV klavyesini kapat. */
    private fun afterClick() {
        if (!remoteConnected) return
        handler.postDelayed({
            hideKeyboard()
            if (urlEdit.hasFocus()) { remote.send("F"); return@postDelayed }
            val w = current
            if (w != null && !showingHome) w.evaluateJavascript(JS_IS_EDIT) { r -> if (r != null && r.contains("1")) remote.send("F") }
        }, 350)
    }

    // ------------------------------------------------------------------ telefon kumandası komutları

    private fun onRemoteClients(n: Int, name: String) {
        val was = remoteConnected
        remoteConnected = n > 0
        tabs.forEach { it.noIme = remoteConnected }
        urlEdit.showSoftInputOnFocus = !remoteConnected
        if (remoteConnected && !was) toast("Telefon kumandası bağlandı: $name")
        if (!remoteConnected && was) toast("Telefon kumandası ayrıldı")
        updateCursorVisibility()
    }

    private fun onRemote(line: String) {
        val sp = line.indexOf(' ')
        val cmd = if (sp < 0) line else line.substring(0, sp)
        val arg = if (sp < 0) "" else line.substring(sp + 1)
        fun xy(): Pair<Float, Float> {
            val p = arg.split(' ')
            return Pair(p.getOrNull(0)?.toFloatOrNull() ?: 0f, p.getOrNull(1)?.toFloatOrNull() ?: 0f)
        }
        when (cmd) {
            "M" -> {
                val (x, y) = xy()
                cursor.visibility = View.VISIBLE
                cursor.centerIfUnset()
                cursor.moveBy(x * density, y * density)
                if (longDown) touchAt(MotionEvent.ACTION_MOVE)
            }
            "C" -> {
                if (activeDialog?.isShowing == true) { remoteKey("CENTER"); return }
                touchAt(MotionEvent.ACTION_DOWN)
                handler.postDelayed({ touchAt(MotionEvent.ACTION_UP); afterClick() }, 40)
            }
            "LD" -> { longDown = true; touchAt(MotionEvent.ACTION_DOWN) }
            "LU" -> { longDown = false; touchAt(MotionEvent.ACTION_UP); afterClick() }
            "SD" -> {
                if (pressed) touchAt(MotionEvent.ACTION_UP)
                dragging = true; dragX = cursor.cx; dragY = cursor.cy
                touchAt(MotionEvent.ACTION_DOWN, dragX, dragY)
            }
            "S" -> if (dragging) {
                val (x, y) = xy()
                dragX += x * density * 1.3f; dragY += y * density * 1.3f
                touchAt(MotionEvent.ACTION_MOVE, dragX, dragY)
            }
            "SE" -> if (dragging) { dragging = false; touchAt(MotionEvent.ACTION_UP, dragX, dragY) }
            "T" -> typeText(URLDecoder.decode(arg, "UTF-8"), 0)
            "B" -> typeText("", arg.toIntOrNull() ?: 1)
            "U" -> go(URLDecoder.decode(arg, "UTF-8"))
            "K" -> remoteKey(arg)
        }
    }

    private fun remoteKey(k: String) {
        val code = when (k) {
            "UP" -> KeyEvent.KEYCODE_DPAD_UP
            "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "CENTER" -> KeyEvent.KEYCODE_DPAD_CENTER
            "BACK" -> KeyEvent.KEYCODE_BACK
            "ENTER" -> KeyEvent.KEYCODE_ENTER
            else -> 0
        }
        val dlg = activeDialog?.takeIf { it.isShowing }
        if (dlg != null && code != 0) {
            dlg.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            dlg.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            return
        }
        when (k) {
            "BACK" -> if (!handleBack()) moveTaskToBack(true)
            "HOME" -> showHome(true)
            "REFRESH" -> current?.reload()
            "FWD" -> current?.let { if (it.canGoForward()) it.goForward() }
            "MENU" -> showMenu()
            "ENTER" -> {
                if (urlEdit.hasFocus()) go(urlEdit.text.toString())
                else current?.let {
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            "UP", "DOWN", "LEFT", "RIGHT", "CENTER" -> {
                bypassCursor = true
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                bypassCursor = false
            }
        }
    }

    /** Telefondan gelen yazıyı TV'deki odaklı alana yaz (n = önce silinecek karakter sayısı). */
    private fun typeText(t: String, n: Int) {
        val w = current
        if (urlEdit.hasFocus() || showingHome || w == null) { typeIntoUrl(t, n); return }
        w.evaluateJavascript(JS_TYPE + "(" + JSONObject.quote(t) + "," + n + ")") { r ->
            if (r != null && r.contains("none")) typeIntoUrl(t, n)
        }
    }

    private fun typeIntoUrl(t: String, n: Int) {
        if (!urlEdit.hasFocus()) { urlEdit.requestFocus(); urlEdit.setSelection(urlEdit.text.length) }
        val ed = urlEdit.text
        var left = n
        var s = min(urlEdit.selectionStart, urlEdit.selectionEnd).coerceAtLeast(0)
        val e = max(urlEdit.selectionStart, urlEdit.selectionEnd).coerceAtLeast(0)
        if (left > 0 && e > s) { ed.delete(s, e); left-- }
        if (left > 0) { val from = max(0, s - left); ed.delete(from, s); s = from }
        if (t.isNotEmpty()) {
            val a = min(urlEdit.selectionStart, urlEdit.selectionEnd).coerceAtLeast(0)
            val b = max(urlEdit.selectionStart, urlEdit.selectionEnd).coerceAtLeast(0)
            ed.replace(a, b, t)
        }
        hideKeyboard()
    }

    // ------------------------------------------------------------------ menüler ve diyaloglar

    private fun builder() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun show(b: AlertDialog.Builder): AlertDialog {
        val d = b.show()
        activeDialog = d
        return d
    }

    private fun listDialog(
        title: String, items: List<String>, onClick: (Int) -> Unit,
        onLong: ((Int) -> Unit)? = null, configure: (AlertDialog.Builder) -> Unit = {}
    ): AlertDialog {
        val b = builder().setTitle(title)
        if (items.isEmpty()) b.setMessage("Henüz bir şey yok.")
        else b.setItems(items.toTypedArray()) { _, i -> onClick(i) }
        configure(b)
        val d = show(b)
        if (onLong != null && items.isNotEmpty())
            d.listView?.setOnItemLongClickListener { _, _, i, _ -> d.dismiss(); onLong(i); true }
        return d
    }

    private fun showMenu() {
        val w = current
        val onPage = !showingHome && w != null
        val items = mutableListOf<Pair<String, () -> Unit>>()
        fun add(t: String, f: () -> Unit) { items.add(Pair(t, f)) }
        add("Yeni sekme") { newTab(false); showHome(false) }
        add("Yeni gizli sekme") { newTab(true); showHome(false) }
        add("Sekmeler (${tabs.size})") { tabsDialog() }
        if (onPage && w != null) {
            add("İleri") { if (w.canGoForward()) w.goForward() }
            add("Yakınlaştır") { w.zoomIn() }
            add("Uzaklaştır") { w.zoomOut() }
            add("Yer imlerine ekle") { addBookmark() }
            add("Hızlı erişime ekle") { editDialDialog(-1, w.title ?: "", w.url ?: "") }
            add("Paylaş") { share(w.url) }
        }
        add("Masaüstü sitesi: " + onOff(store.desktopMode)) { toggleDesktop() }
        add("Kumanda imleci: " + onOff(cursorMode)) { toggleCursor() }
        add("Yer imleri") { bookmarksDialog() }
        add("Geçmiş") { historyDialog() }
        add("İndirilenler") { downloadsDialog() }
        add("Ayarlar") { settingsDialog() }
        add("Çıkış") { finishAndRemoveTask() }
        listDialog("Menü", items.map { it.first }, { items[it].second() })
    }

    private fun onOff(b: Boolean) = if (b) "Açık" else "Kapalı"

    private fun tabsDialog() {
        val names = tabs.map { t ->
            val mark = if (t === current) "● " else ""
            val inc = if (t.incognito) "[Gizli] " else ""
            mark + inc + (t.title?.takeIf { it.isNotBlank() } ?: t.url ?: t.pending ?: "Yeni sekme")
        }
        listDialog("Sekmeler (silmek için uzun basın)", names, { i ->
            current = tabs[i]
            val c = tabs[i]
            if (c.url == null && c.pending == null) showHome(false) else showWeb()
        }, { i -> closeTab(tabs[i]) }) { b ->
            b.setPositiveButton("Yeni sekme") { _, _ -> newTab(false); showHome(false) }
            b.setNeutralButton("Tümünü kapat") { _, _ -> tabs.toList().forEach { closeTab(it) } }
        }
    }

    private fun addBookmark() {
        val w = current ?: return
        val url = w.url ?: return
        val bm = store.bookmarks
        bm.removeAll { it.url == url }
        bm.add(0, Site(w.title ?: url, url))
        store.bookmarks = bm
        toast("Yer imlerine eklendi")
    }

    private fun bookmarksDialog() {
        val bm = store.bookmarks
        listDialog("Yer imleri (silmek için uzun basın)", bm.map { it.title }, { go(bm[it].url) }, { i ->
            val l = store.bookmarks; l.removeAt(i); store.bookmarks = l; toast("Silindi"); bookmarksDialog()
        }) { b -> if (!showingHome && current?.url != null) b.setPositiveButton("Bu sayfayı ekle") { _, _ -> addBookmark() } }
    }

    private fun historyDialog() {
        val h = store.history.take(200)
        listDialog("Geçmiş", h.map { it.title + "\n" + it.url }, { go(h[it].url) }, { i ->
            val l = store.history; l.removeAll { it.url == h[i].url }; store.history = l; historyDialog()
        }) { b -> b.setNeutralButton("Geçmişi temizle") { _, _ -> store.history = mutableListOf(); toast("Geçmiş temizlendi") } }
    }

    private fun clearDataDialog() {
        show(builder().setTitle("Verileri temizle")
            .setMessage("Geçmiş, çerezler, site verileri ve önbellek silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                store.history = mutableListOf()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.clearCache(true); it.clearHistory() }
                toast("Temizlendi")
            }
            .setNegativeButton("Vazgeç", null))
    }

    private fun customizeDialog() {
        val d = store.speedDial
        listDialog("Hızlı erişim", d.map { it.title + "  —  " + it.url }, { dialOptions(it) }) { b ->
            b.setPositiveButton("Ekle") { _, _ -> editDialDialog(-1, "", "") }
        }
    }

    private fun dialOptions(i: Int) {
        val d = store.speedDial
        if (i !in d.indices) return
        listDialog(d[i].title, listOf("Düzenle", "Sil", "Başa taşı"), { c ->
            val l = store.speedDial
            when (c) {
                0 -> editDialDialog(i, l[i].title, l[i].url)
                1 -> { l.removeAt(i); store.speedDial = l }
                2 -> { val s = l.removeAt(i); l.add(0, s); store.speedDial = l }
            }
            if (showingHome) refreshDial()
        })
    }

    private fun editDialDialog(index: Int, title: String, url: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val t = EditText(this).apply { hint = "Ad"; setText(title); setSingleLine() }
        val u = EditText(this).apply {
            hint = "Adres (örn. youtube.com)"; setText(url); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(t); box.addView(u)
        show(builder().setTitle(if (index < 0) "Hızlı erişime ekle" else "Düzenle").setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val raw = u.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val full = if (raw.contains("://")) raw else "https://$raw"
                val name = t.text.toString().trim().ifEmpty { Uri.parse(full).host?.removePrefix("www.") ?: raw }
                val l = store.speedDial
                if (index in l.indices) l[index] = Site(name, full) else l.add(Site(name, full))
                store.speedDial = l
                if (showingHome) refreshDial()
            }
            .setNegativeButton("Vazgeç", null))
    }

    private fun settingsDialog() {
        val speeds = arrayOf("Yavaş", "Orta", "Hızlı")
        val zooms = arrayOf(80, 90, 100, 110, 125, 150, 175)
        val items = listOf(
            "Arama motoru: " + Store.ENGINES[store.searchEngine.coerceIn(0, 3)],
            "Reklam engelleyici: " + onOff(store.adBlock),
            "Açılır pencere engeli: " + onOff(store.popupBlock),
            "Masaüstü sitesi: " + onOff(store.desktopMode),
            "Kumanda imleci (TV kumandası): " + onOff(cursorMode),
            "İmleç hızı: " + speeds[(store.cursorSpeed - 1).coerceIn(0, 2)],
            "Yazı boyutu: %" + store.textZoom,
            "Telefon kumandası adresi: " + RemoteServer.localIp() + ":" + remote.port,
            "Varsayılan tarayıcı yap",
            "Sürüm: " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" })
        )
        listDialog("Ayarlar", items, { i ->
            when (i) {
                0 -> show(builder().setTitle("Arama motoru").setSingleChoiceItems(Store.ENGINES, store.searchEngine) { d, w ->
                    store.searchEngine = w; d.dismiss(); settingsDialog()
                })
                1 -> { store.adBlock = !store.adBlock; settingsDialog() }
                2 -> {
                    store.popupBlock = !store.popupBlock
                    tabs.forEach { it.settings.javaScriptCanOpenWindowsAutomatically = !store.popupBlock }
                    settingsDialog()
                }
                3 -> { toggleDesktop(); settingsDialog() }
                4 -> { toggleCursor(); settingsDialog() }
                5 -> show(builder().setTitle("İmleç hızı").setSingleChoiceItems(speeds, store.cursorSpeed - 1) { d, w ->
                    store.cursorSpeed = w + 1; d.dismiss(); settingsDialog()
                })
                6 -> show(builder().setTitle("Yazı boyutu").setSingleChoiceItems(zooms.map { "%$it" }.toTypedArray(),
                    zooms.indexOf(store.textZoom)) { d, w ->
                    store.textZoom = zooms[w]; tabs.forEach { it.settings.textZoom = zooms[w] }; d.dismiss(); settingsDialog()
                })
                7 -> remoteTile()
                8 -> try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (_: Exception) { toast("Bu cihazda açılamadı") }
                else -> {}
            }
        })
    }

    private fun toggleDesktop() {
        store.desktopMode = !store.desktopMode
        tabs.forEach { it.settings.userAgentString = ua() }
        current?.reload()
        toast("Masaüstü sitesi: " + onOff(store.desktopMode))
    }

    private fun toggleCursor() {
        cursorMode = !cursorMode
        store.setCursorDefault(cursorMode)
        updateCursorVisibility()
        toast("Kumanda imleci: " + onOff(cursorMode))
    }

    /** Ana ekrandaki "Kumanda" düğmesi. Telefonda kumanda ekranını açar, TV'de bağlantı bilgisini gösterir. */
    private fun remoteTile() {
        if (!isTv) {
            startActivity(Intent(this, RemoteActivity::class.java))
            return
        }
        val msg = "Telefonunuzda \"Jet Kumanda\" uygulamasını açın. TV ile telefon aynı Wi-Fi ağında olmalı.\n\n" +
            "Otomatik bulunmazsa bu adresi girin:\n" + RemoteServer.localIp() + ":" + remote.port +
            "\n\nDurum: " + (if (remoteConnected) "Telefon bağlı" else "Telefon bekleniyor")
        show(builder().setTitle("Telefon kumandası").setMessage(msg)
            .setPositiveButton("Tamam", null)
            .setNeutralButton("TV imleci: " + onOff(cursorMode)) { _, _ -> toggleCursor() })
    }

    private fun share(url: String?) {
        url ?: return
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Paylaş"))
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ indirme

    private fun download(url: String, ua: String?, cd: String?, mime: String?) {
        if (!url.startsWith("http")) { toast("Bu dosya indirilemiyor"); return }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 3)
            toast("İzin verip tekrar deneyin")
            return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        show(builder().setTitle("İndirilsin mi?").setMessage(name)
            .setPositiveButton("İndir") { _, _ ->
                try {
                    val r = DownloadManager.Request(Uri.parse(url))
                        .setTitle(name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    if (mime != null) r.setMimeType(mime)
                    CookieManager.getInstance().getCookie(url)?.let { r.addRequestHeader("Cookie", it) }
                    if (ua != null) r.addRequestHeader("User-Agent", ua)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
                    toast("İndiriliyor: $name")
                } catch (e: Exception) { toast("İndirme başlatılamadı") }
            }
            .setNegativeButton("Vazgeç", null))
    }

    private fun downloadsDialog() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val ids = mutableListOf<Long>()
        val names = mutableListOf<String>()
        try {
            dm.query(DownloadManager.Query()).use { c ->
                val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val iT = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val iS = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (c.moveToNext()) {
                    val st = when (c.getInt(iS)) {
                        DownloadManager.STATUS_SUCCESSFUL -> "✓"
                        DownloadManager.STATUS_FAILED -> "✗"
                        else -> "…"
                    }
                    ids.add(c.getLong(iId)); names.add("$st  " + c.getString(iT))
                }
            }
        } catch (_: Exception) { }
        ids.reverse(); names.reverse()
        val received = (FileShare.dir(this).listFiles() ?: arrayOf()).sortedByDescending { it.lastModified() }
        val labels = received.map { "📥  " + it.name } + names
        listDialog("İndirilenler (silmek için uzun basın)", labels, { i ->
            if (i < received.size) { openReceived(received[i]); return@listDialog }
            val id = ids[i - received.size]
            val uri = dm.getUriForDownloadedFile(id)
            if (uri == null) { toast("Dosya hazır değil"); return@listDialog }
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { toast("Bu dosyayı açacak uygulama yok") }
        }, { i ->
            if (i < received.size) received[i].delete() else dm.remove(ids[i - received.size])
            toast("Silindi")
        })
    }

    // ------------------------------------------------------------------ sesli arama, yardımcılar

    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 7)
        } catch (_: Exception) { toast("Bu cihazda sesli arama yok") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK)
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { go(it) }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val JS_FIND =
            "var d=document,e=d.activeElement;" +
                "while(e&&e.tagName=='IFRAME'){try{d=e.contentDocument;e=d.activeElement}catch(x){break}}" +
                "var ok=e&&(e.isContentEditable||e.tagName=='TEXTAREA'||(e.tagName=='INPUT'&&!/^(button|submit|checkbox|radio|range|color|file|image|reset)$/i.test(e.type)));"

        val JS_IS_EDIT = "(function(){$JS_FIND return ok?'1':'0';})()"

        val JS_TYPE = "(function(t,n){$JS_FIND if(!ok)return 'none';" +
            "for(var i=0;i<n;i++)d.execCommand('delete',false);" +
            "if(t&&!d.execCommand('insertText',false,t)&&('value' in e)){" +
            "var s=e.selectionStart==null?e.value.length:e.selectionStart,f=e.selectionEnd==null?s:e.selectionEnd;" +
            "e.value=e.value.slice(0,s)+t+e.value.slice(f);e.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "return 'ok';})"
    }
}
