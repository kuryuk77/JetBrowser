package com.kuryuk.jetbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
        remote = Hub.start(this)
        Hub.owner = this
        Hub.onCommand = ::onRemote
        Hub.onClients = ::onRemoteClients
        Hub.onFile = { ev, name, file ->
            when (ev) {
                0 -> toast("Telefondan dosya alınıyor: $name")
                1 -> if (file != null) onFileReceived(file)
                else -> toast("Dosya alınamadı: $name")
            }
        }
        if (isTv) Hub.startService(this)
        wakeScreen(intent)
        restoreTabs()
        if (!handleIntent(intent)) showHome(false)
        if (Hub.clientCount > 0) onRemoteClients(Hub.clientCount, Hub.clientName)
        Hub.pendingFile?.let { Hub.pendingFile = null; root.post { onFileReceived(it) } }
        if (isTv && !canWake() && !getSharedPreferences("jet", MODE_PRIVATE).getBoolean("askedWake", false)) {
            getSharedPreferences("jet", MODE_PRIVATE).edit().putBoolean("askedWake", true).apply()
            root.post { wakePermissionPanel() }
        }
    }

    private fun usageGranted() = try {
        val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun canWake() = if (Hub.hasAccessBuild(this)) JetAccess.instance != null
        else (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) && usageGranted()

    /** Normal kurulumda iki ayrı izin: üstte gösterme (uzaktan açma) ve kullanım erişimi (önceki uygulama). */
    private fun litePermissionPanel() {
        val overlay = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)
        val usage = usageGranted()
        fun mark(b: Boolean) = if (b) "✓ verildi" else "✗ verilmedi"
        val buttons = mutableListOf<Pair<String, () -> Unit>>(Pair("Kapat") { })
        if (!usage) buttons.add(Pair("Kullanım erişimi") {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            catch (_: Exception) { toast("TV Ayarları > Uygulamalar > Özel uygulama erişimi > Kullanım erişimi") }
        })
        if (!overlay) buttons.add(Pair("Üstte göster") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                catch (_: Exception) { toast("TV Ayarları > Uygulamalar > Özel uygulama erişimi > Diğer uygulamaların üzerinde göster") }
            }
        })
        openPanel("Telefondan kontrol izinleri",
            "Telefonla uygulama açmak ve uygulamalar arasında geçmek için bu TV'de iki izin gerekiyor:\n\n" +
                "1) Diğer uygulamaların üzerinde göster: " + mark(overlay) + "\n" +
                "    (JetBrowser kapalıyken uygulama açabilmek için)\n\n" +
                "2) Kullanım erişimi: " + mark(usage) + "\n" +
                "    (\"Önceki uygulama\" ve son kullanılanlar listesi için)\n\n" +
                "Açılan listede JetBrowser'ı bulup izni açın, sonra geri dönün.",
            null, buttons)
    }

    /** Telefondan "TV'de aç" ile gelindiyse ekranı uyandırmayı dene. */
    private fun wakeScreen(i: Intent?) {
        if (i?.getBooleanExtra("wake", false) != true) return
        if (Build.VERSION.SDK_INT >= 27) { setTurnScreenOn(true); setShowWhenLocked(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun wakePermissionPanel() {
        if (!Hub.hasAccessBuild(this)) { litePermissionPanel(); return }
        openPanel("Telefondan tam kontrol",
            "Telefonla TV'yi kumandasız yönetmek için bu TV'de bir kez izin vermeniz gerekiyor:\n\n" +
                "Erişilebilirlik ayarlarında JetBrowser'ı bulun ve açın.\n\n" +
                "Bununla touchpad imleci, ok tuşları ve klavye tüm TV'de çalışır. " +
                "Hiçbir veri kaydedilmez ya da TV dışına gönderilmez.",
            null, listOf(Pair("Sonra") { }, Pair("Ayarları aç") {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) { toast("TV Ayarları > Erişilebilirlik bölümünden JetBrowser'ı açın") }
            }))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        wakeScreen(intent)
        handleIntent(intent)
        Hub.pendingFile?.let { Hub.pendingFile = null; onFileReceived(it) }
    }

    private var pendingApk: File? = null

    override fun onResume() {
        super.onResume()
        Hub.foreground = true
        val f = pendingApk
        if (f != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
            pendingApk = null
            installApk(f)
        }
    }

    // ------------------------------------------------------------------ telefondan gelen dosyalar

    private fun onFileReceived(f: File) {
        if (f.name.lowercase().endsWith(".apk")) { installApk(f); return }
        openPanel("Dosya alındı", f.name, null, listOf(Pair("Tamam") { }, Pair("Aç") { openReceived(f) }))
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
        Hub.foreground = false
        saveTabs()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (Hub.owner === this) {
            Hub.owner = null; Hub.onCommand = null; Hub.onClients = null; Hub.onFile = null
            if (!isTv) Hub.stop()
        }
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

    private lateinit var topHolder: FrameLayout
    private lateinit var homeHolder: FrameLayout
    private lateinit var actionGrid: GridLayout
    private lateinit var btnHome: View
    private lateinit var btnBack: View
    private lateinit var btnRefresh: View
    private lateinit var btnAdd: View
    private val panels = mutableListOf<View>()

    /** Cam görünümlü arka plan: yarı saydam dolgu, ince çizgi; odakta sarı çerçeve. */
    private fun glassBg(oval: Boolean, radiusDp: Int = 16): Drawable {
        fun shape(fill: Int, stroke: Int, sw: Int) = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(sw), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(SURFACE_FOCUS, ACCENT, 2))
            addState(intArrayOf(android.R.attr.state_pressed), shape(SURFACE_FOCUS, 0x55FFFFFF, 1))
            addState(intArrayOf(), shape(SURFACE, 0x2EFFFFFF, 1))
        }
    }

    private fun screenW() = resources.displayMetrics.widthPixels

    private fun buildUi() {
        root = FrameLayout(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        btnHome = iconBtn(R.drawable.ic_home) { showHome(true) }
        btnBack = iconBtn(R.drawable.ic_back) { handleBack() }
        btnRefresh = iconBtn(R.drawable.ic_refresh) { current?.reload() }
        bar.addView(btnHome)
        bar.addView(btnBack)
        bar.addView(btnRefresh)

        val urlBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBg(false, 24)
            setPadding(dp(16), 0, dp(2), 0)
        }
        urlEdit = EditText(this).apply {
            hint = "Ara veya adres yaz"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = null
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            setHintTextColor(TEXT_DIM)
            textSize = 16f
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_GO || enter) { go(text.toString()); true } else false
            }
        }
        urlBox.addView(urlEdit, LinearLayout.LayoutParams(0, dp(46), 1f))
        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = glassBg(true)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { voice() }
        }
        urlBox.addView(mic, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(3) })
        bar.addView(urlBox, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })

        btnAdd = iconBtn(R.drawable.ic_add) { newTab(false); showHome(false) }
        bar.addView(btnAdd)
        tabCount = TextView(this).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setStroke(dp(2), Color.WHITE); cornerRadius = dp(5).toFloat()
            }
        }
        val tabWrap = FrameLayout(this).apply {
            background = glassBg(true)
            isFocusable = true
            isClickable = true
            setOnClickListener { tabsDialog() }
            addView(tabCount, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        bar.addView(tabWrap, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(3), 0, dp(3), 0) })
        bar.addView(iconBtn(R.drawable.ic_more) { showMenu() })

        topHolder = FrameLayout(this)
        column.addView(topHolder, LinearLayout.LayoutParams(-1, -2))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
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
        background = glassBg(true)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(3), 0, dp(3), 0) }
    }

    /** Adres çubuğu ana ekranda logonun altında, sayfa açıkken en üstte durur. */
    private fun placeBar(home: Boolean) {
        val target = if (home) homeHolder else topHolder
        if (bar.parent !== target) {
            (bar.parent as? ViewGroup)?.removeView(bar)
            target.addView(bar, FrameLayout.LayoutParams(-1, -2))
        }
        val wide = screenW() / density >= 600
        btnHome.visibility = if (home) View.GONE else View.VISIBLE
        val extra = if (wide && !home) View.VISIBLE else View.GONE
        btnBack.visibility = extra
        btnRefresh.visibility = extra
        btnAdd.visibility = extra
        homeHolder.layoutParams = homeHolder.layoutParams.apply { width = min(screenW() - dp(16), dp(680)) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post { placeBar(showingHome); if (showingHome) refreshHome() }
    }

    private fun buildHome() {
        homeView = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(36), dp(8), dp(24))
        }
        val logo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        logo.addView(ImageView(this).apply { setImageResource(R.drawable.ic_jet) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        logo.addView(TextView(this).apply {
            text = SpannableString("JetBrowser").apply { setSpan(ForegroundColorSpan(ACCENT), 0, 3, 0) }
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, 0, 0)
        })
        col.addView(logo)

        homeHolder = FrameLayout(this)
        col.addView(homeHolder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        dialGrid = GridLayout(this)
        col.addView(dialGrid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })

        actionGrid = GridLayout(this)
        col.addView(actionGrid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        homeView.addView(col)
    }

    private fun labelView(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFFE6E6E6.toInt())
        textSize = 12.5f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(6), dp(2), 0)
    }

    private fun refreshHome() {
        val avail = screenW() - dp(16)
        val cols = (avail / dp(104)).coerceIn(4, 8)
        val cell = min(avail / cols, dp(112))

        dialGrid.removeAllViews()
        dialGrid.columnCount = cols
        store.speedDial.forEachIndexed { i, s ->
            val t = dialTile(cell, iconFor(s.url), s.title) { go(s.url) }
            t.setOnLongClickListener { dialOptions(i); true }
            dialGrid.addView(t)
        }
        dialGrid.addView(dialTile(cell, null, "Ekle", plus = true) { editDialDialog(-1, "", current?.url ?: "") })

        actionGrid.removeAllViews()
        actionGrid.columnCount = if (cols >= 8) 8 else 4
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
        items.forEach { (ic, label, action) -> actionGrid.addView(actionTile(cell, ic, label, action)) }
    }

    private fun tile(cell: Int, box: View, boxSize: Int, title: String, onClick: () -> Unit): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }
        box.isDuplicateParentStateEnabled = true
        v.addView(box, LinearLayout.LayoutParams(boxSize, boxSize))
        v.addView(labelView(title), LinearLayout.LayoutParams(-1, -2))
        v.layoutParams = GridLayout.LayoutParams().apply { width = cell }
        return v
    }

    private fun actionTile(cell: Int, icon: Int, label: String, onClick: () -> Unit): View {
        val box = ImageView(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = glassBg(true)
        }
        return tile(cell, box, min(cell - dp(20), dp(60)), label, onClick)
    }

    private fun dialTile(cell: Int, icon: Bitmap?, title: String, plus: Boolean = false, onClick: () -> Unit): View {
        val box = FrameLayout(this).apply { background = glassBg(true) }
        if (icon != null) {
            box.addView(ImageView(this).apply { setImageBitmap(icon) }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        } else {
            box.addView(TextView(this).apply {
                text = if (plus) "+" else title.trim().take(1).uppercase()
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (plus) Color.WHITE else ACCENT)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, FrameLayout.LayoutParams(-1, -1))
        }
        return tile(cell, box, min(cell - dp(16), dp(72)), title, onClick)
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
        placeBar(false)
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
        placeBar(true)
        refreshHome()
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
        if (panels.isNotEmpty()) { closePanel(panels.last()); return true }
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
            handleUrl(view, request.url.toString(), request.hasGesture())

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
            externalOpen(view, intent)
        } catch (_: Exception) { toast("Açılamadı") }
        return true
    }

    /** Sayfa başka bir uygulama açmak isterse sistemin "Şununla aç" penceresi yerine kendi panelimizde sor. */
    private fun externalOpen(view: WebView, intent: Intent) {
        if (panels.isNotEmpty()) return
        val fb = intent.getStringExtra("browser_fallback_url")
        val apps = try {
            packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != packageName && it.activityInfo.exported }
        } catch (_: Exception) { emptyList() }
        if (apps.isEmpty()) {
            if (fb != null) view.loadUrl(fb) else toast("Bunu açacak uygulama yok")
            return
        }
        listPanel("Sayfa başka uygulama açmak istiyor", apps.map { it.loadLabel(packageManager).toString() }, { i ->
            try {
                startActivity(Intent(intent).setClassName(apps[i].activityInfo.packageName, apps[i].activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { toast("Açılamadı") }
        }, null, listOf(Pair("Vazgeç") { }))
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
        cursorMode && !bypassCursor && panels.isEmpty() && !bar.hasFocus() &&
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
                if (web.canScrollVertically(-1)) edgePush(-1)
                else { btnHome.requestFocus() }
                return
            }
            if (dy > 0 && cursor.cy >= root.height - edge - 1) { edgePush(1); return }
            if (dx < 0 && cursor.cx <= edge) { if (web.canScrollHorizontally(-1)) web.scrollBy(-scroll, 0); return }
            if (dx > 0 && cursor.cx >= root.width - edge - 1) { if (web.canScrollHorizontally(1)) web.scrollBy(scroll, 0); return }
        }
        cursor.moveBy(dx, dy)
    }

    // imleç ekranın altına / üstüne dayanınca sayfa yavaşça kayar; itmeyi bırakınca durur
    private var edgeDir = 0
    private var edgeUntil = 0L
    private var edgeRunning = false
    private val edgeScroll = object : Runnable {
        override fun run() {
            val w = current
            if (w == null || showingHome || customView != null || panels.isNotEmpty() ||
                SystemClock.uptimeMillis() > edgeUntil) { edgeRunning = false; return }
            if (w.canScrollVertically(edgeDir)) w.scrollBy(0, edgeDir * dp(5))
            handler.postDelayed(this, 16)
        }
    }

    private fun edgePush(dir: Int) {
        edgeDir = dir
        edgeUntil = SystemClock.uptimeMillis() + 300
        if (!edgeRunning) { edgeRunning = true; handler.post(edgeScroll) }
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
            if (currentFocus is EditText) { remote.send("F"); return@postDelayed }
            if (panels.isNotEmpty()) return@postDelayed
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
                else if (y > 0 && cursor.cy >= root.height - 2) edgePush(1)
                else if (y < 0 && cursor.cy <= 1f) edgePush(-1)
            }
            "C" -> {
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
        when (k) {
            "BACK" -> if (!handleBack()) moveTaskToBack(true)
            "HOME" -> showHome(true)
            "REFRESH" -> current?.reload()
            "FWD" -> current?.let { if (it.canGoForward()) it.goForward() }
            "MENU" -> showMenu()
            "ENTER" -> {
                val f = currentFocus
                if (f === urlEdit) go(urlEdit.text.toString())
                else if (f is EditText) hideKeyboard()
                else if (panels.isNotEmpty()) remoteKey("CENTER")
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
        val f = currentFocus
        if (f is EditText) { typeInto(f, t, n); return }
        if (panels.isNotEmpty()) return
        val w = current
        if (showingHome || w == null) { typeInto(urlEdit, t, n); return }
        w.evaluateJavascript(JS_TYPE + "(" + JSONObject.quote(t) + "," + n + ")") { r ->
            if (r != null && r.contains("none")) typeInto(urlEdit, t, n)
        }
    }

    private fun typeInto(target: EditText, t: String, n: Int) {
        if (!target.hasFocus()) { target.requestFocus(); target.setSelection(target.text.length) }
        val ed = target.text
        var left = n
        var s = min(target.selectionStart, target.selectionEnd).coerceAtLeast(0)
        val e = max(target.selectionStart, target.selectionEnd).coerceAtLeast(0)
        if (left > 0 && e > s) { ed.delete(s, e); left-- }
        if (left > 0) { val from = max(0, s - left); ed.delete(from, s); s = from }
        if (t.isNotEmpty()) {
            val a = min(target.selectionStart, target.selectionEnd).coerceAtLeast(0)
            val b = max(target.selectionStart, target.selectionEnd).coerceAtLeast(0)
            ed.replace(a, b, t)
        }
        hideKeyboard()
    }

    // ------------------------------------------------------------------ cam paneller (menü ve listeler)

    private fun openPanel(title: String, message: String?, body: View?, buttons: List<Pair<String, () -> Unit>>): View {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x8C000000.toInt())
            isClickable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(14), dp(16), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(GLASS_STRONG); cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0x33FFFFFF)
            }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), dp(10))
        })
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (message != null) inner.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(TEXT_DIM)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(8), dp(2), dp(8), dp(10))
        })
        if (body != null) inner.addView(body, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { addView(inner) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(10), 0, 0) }
            buttons.forEach { (t, action) ->
                row.addView(TextView(this).apply {
                    text = t
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ACCENT)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(dp(18), dp(11), dp(18), dp(11))
                    background = glassBg(false, 24)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { closePanel(scrim); action() }
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        scrim.setOnClickListener { closePanel(scrim) }
        scrim.addView(card, FrameLayout.LayoutParams(min(screenW() - dp(28), dp(540)), -2, Gravity.CENTER).apply {
            topMargin = dp(28); bottomMargin = dp(28)
        })
        (panels.lastOrNull() as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        column.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        hideKeyboard()
        root.addView(scrim, root.indexOfChild(cursor), FrameLayout.LayoutParams(-1, -1))
        panels.add(scrim)
        scrim.post { card.getFocusables(View.FOCUS_DOWN).firstOrNull()?.requestFocus() }
        updateCursorVisibility()
        return scrim
    }

    private fun closePanel(v: View) {
        if (!panels.remove(v)) return
        hideKeyboard()
        root.removeView(v)
        val top = panels.lastOrNull() as? ViewGroup
        if (top != null) top.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        else {
            column.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (!showingHome) current?.requestFocus()
            else if (isTv && !remoteConnected) dialGrid.getChildAt(0)?.requestFocus()
        }
        updateCursorVisibility()
    }

    private fun closeAllPanels() = panels.toList().reversed().forEach { closePanel(it) }

    private fun confirm(title: String, message: String, ok: String, action: () -> Unit) =
        openPanel(title, message, null, listOf(Pair("Vazgeç") { }, Pair(ok, action)))

    private fun listPanel(
        title: String, items: List<String>, onClick: (Int) -> Unit,
        onLong: ((Int) -> Unit)? = null,
        buttons: List<Pair<String, () -> Unit>> = emptyList(), selected: Int = -1
    ): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var panel: View? = null
        items.forEachIndexed { i, s ->
            val row = TextView(this).apply {
                text = if (i == selected) "✓  $s" else s
                textSize = 16f
                setTextColor(if (i == selected) ACCENT else Color.WHITE)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = glassBg(false, 16)
                isFocusable = true
                isClickable = true
                setOnClickListener { panel?.let { closePanel(it) }; onClick(i) }
                if (onLong != null) setOnLongClickListener { panel?.let { closePanel(it) }; onLong(i); true }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        val p = openPanel(title, if (items.isEmpty()) "Henüz bir şey yok." else null, list, buttons)
        panel = p
        return p
    }

    private fun field(hintText: String, value: String, uri: Boolean = false) = EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine()
        setTextColor(Color.WHITE)
        setHintTextColor(TEXT_DIM)
        background = glassBg(false, 16)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        showSoftInputOnFocus = !remoteConnected
    }

    private fun showMenu() {
        if (panels.isNotEmpty()) { closeAllPanels(); return }
        val w = current
        val onPage = !showingHome && w != null
        val items = mutableListOf<Pair<String, () -> Unit>>()
        fun add(t: String, f: () -> Unit) { items.add(Pair(t, f)) }
        add("Yeni sekme") { newTab(false); showHome(false) }
        add("Yeni gizli sekme") { newTab(true); showHome(false) }
        add("Sekmeler (${tabs.size})") { tabsDialog() }
        if (onPage && w != null) {
            add("Yenile") { w.reload() }
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
        listPanel("Menü", items.map { it.first }, { items[it].second() })
    }

    private fun onOff(b: Boolean) = if (b) "Açık" else "Kapalı"

    private fun tabsDialog() {
        val names = tabs.map { t ->
            (if (t.incognito) "[Gizli] " else "") +
                (t.title?.takeIf { it.isNotBlank() } ?: t.url ?: t.pending ?: "Yeni sekme")
        }
        listPanel("Sekmeler (kapatmak için uzun basın)", names, { i ->
            current = tabs[i]
            val c = tabs[i]
            if (c.url == null && c.pending == null) showHome(false) else showWeb()
        }, { i -> closeTab(tabs[i]); if (tabs.isNotEmpty()) tabsDialog() },
            listOf(
                Pair("Tümünü kapat") { tabs.toList().forEach { closeTab(it) } },
                Pair("Yeni sekme") { newTab(false); showHome(false) }
            ), tabs.indexOf(current))
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
        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        if (!showingHome && current?.url != null) buttons.add(Pair("Bu sayfayı ekle") { addBookmark() })
        listPanel("Yer imleri (silmek için uzun basın)", bm.map { it.title }, { go(bm[it].url) }, { i ->
            val l = store.bookmarks; l.removeAt(i); store.bookmarks = l; toast("Silindi"); bookmarksDialog()
        }, buttons)
    }

    private fun historyDialog() {
        val h = store.history.take(200)
        listPanel("Geçmiş", h.map { it.title + "\n" + it.url }, { go(h[it].url) }, { i ->
            val l = store.history; l.removeAll { it.url == h[i].url }; store.history = l; historyDialog()
        }, listOf(Pair("Geçmişi temizle") { store.history = mutableListOf(); toast("Geçmiş temizlendi") }))
    }

    private fun clearDataDialog() {
        confirm("Verileri temizle", "Geçmiş, çerezler, site verileri ve önbellek silinsin mi?", "Sil") {
            store.history = mutableListOf()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            tabs.forEach { it.clearCache(true); it.clearHistory() }
            toast("Temizlendi")
        }
    }

    private fun customizeDialog() {
        val d = store.speedDial
        listPanel("Hızlı erişim", d.map { it.title + "\n" + it.url }, { dialOptions(it) }, null,
            listOf(Pair("Ekle") { editDialDialog(-1, "", "") }))
    }

    private fun dialOptions(i: Int) {
        val d = store.speedDial
        if (i !in d.indices) return
        listPanel(d[i].title, listOf("Düzenle", "Sil", "Başa taşı"), { c ->
            val l = store.speedDial
            when (c) {
                0 -> editDialDialog(i, l[i].title, l[i].url)
                1 -> { l.removeAt(i); store.speedDial = l }
                2 -> { val s = l.removeAt(i); l.add(0, s); store.speedDial = l }
            }
            if (showingHome) refreshHome()
        })
    }

    private fun editDialDialog(index: Int, title: String, url: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = field("Ad", title)
        val u = field("Adres (örn. youtube.com)", url, true)
        box.addView(t, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(4), dp(2), dp(6)) })
        box.addView(u, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        openPanel(if (index < 0) "Hızlı erişime ekle" else "Düzenle", null, box, listOf(
            Pair("Vazgeç") { },
            Pair("Kaydet") {
                val raw = u.text.toString().trim()
                if (raw.isNotEmpty()) {
                    val full = if (raw.contains("://")) raw else "https://$raw"
                    val name = t.text.toString().trim().ifEmpty { Uri.parse(full).host?.removePrefix("www.") ?: raw }
                    val l = store.speedDial
                    if (index in l.indices) l[index] = Site(name, full) else l.add(Site(name, full))
                    store.speedDial = l
                    if (showingHome) refreshHome()
                }
            }
        ))
    }

    private fun settingsDialog() {
        val speeds = listOf("Yavaş", "Orta", "Hızlı")
        val zooms = listOf(80, 90, 100, 110, 125, 150, 175)
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
            "Güncellemeyi denetle",
            "Sürüm: " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" })
        )
        listPanel("Ayarlar", items, { i ->
            when (i) {
                0 -> listPanel("Arama motoru", Store.ENGINES.toList(), { w -> store.searchEngine = w; settingsDialog() },
                    selected = store.searchEngine)
                1 -> { store.adBlock = !store.adBlock; settingsDialog() }
                2 -> {
                    store.popupBlock = !store.popupBlock
                    tabs.forEach { it.settings.javaScriptCanOpenWindowsAutomatically = !store.popupBlock }
                    settingsDialog()
                }
                3 -> { toggleDesktop(); settingsDialog() }
                4 -> { toggleCursor(); settingsDialog() }
                5 -> listPanel("İmleç hızı", speeds, { w -> store.cursorSpeed = w + 1; settingsDialog() },
                    selected = store.cursorSpeed - 1)
                6 -> listPanel("Yazı boyutu", zooms.map { "%$it" }, { w ->
                    store.textZoom = zooms[w]; tabs.forEach { it.settings.textZoom = zooms[w] }; settingsDialog()
                }, selected = zooms.indexOf(store.textZoom))
                7 -> remoteTile()
                8 -> try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (_: Exception) { toast("Bu cihazda açılamadı") }
                else -> checkUpdate()
            }
        })
    }

    private fun checkUpdate() {
        toast("Denetleniyor…")
        @Suppress("DEPRECATION")
        val mine = try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (_: Exception) { 0 }
        Thread {
            val latest = Hub.latestBuild()
            runOnUiThread {
                if (latest == null) toast("Denetlenemedi. İnternet bağlantısını kontrol edin")
                else if (latest <= mine) toast("Güncel: 1.0.$mine")
                else confirm("Güncelleme var", "Yeni sürüm: 1.0.$latest\nSizdeki: 1.0.$mine", "İndir ve kur") {
                    toast("İndiriliyor…")
                    Thread {
                        val f = Hub.downloadLatest(this)
                        runOnUiThread { if (f == null) toast("İndirilemedi") else installApk(f) }
                    }.start()
                }
            }
        }.start()
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
        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        if (!canWake()) buttons.add(Pair("İzinler") { wakePermissionPanel() })
        buttons.add(Pair("İmleç: " + onOff(cursorMode)) { toggleCursor() })
        buttons.add(Pair("Tamam") { })
        openPanel("Telefon kumandası", msg, null, buttons)
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
        confirm("İndirilsin mi?", name, "İndir") {
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
        listPanel("İndirilenler (silmek için uzun basın)", labels, { i ->
            if (i < received.size) { openReceived(received[i]); return@listPanel }
            val id = ids[i - received.size]
            val uri = dm.getUriForDownloadedFile(id)
            if (uri == null) { toast("Dosya hazır değil"); return@listPanel }
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
        const val ACCENT = 0xFFFFC83D.toInt()
        const val SURFACE = 0x26FFFFFF
        const val SURFACE_FOCUS = 0x40FFFFFF
        const val GLASS_STRONG = 0xEB1E1F23.toInt()
        const val TEXT_DIM = 0xFFB5B8BE.toInt()
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
