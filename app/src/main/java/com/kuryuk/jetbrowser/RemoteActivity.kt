package com.kuryuk.jetbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Telefonu TV'deki JetBrowser için touchpad + klavye olarak kullanır. */
class RemoteActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: PrintWriter? = null
    private var connected = false
    private var destroyed = false
    private var connName = ""
    @Volatile private var curHost: String? = null
    private var curPort = REMOTE_PORT
    private val pendingShare = mutableListOf<Uri>()
    private var sendingFiles = false
    private var pendingUrl: String? = null
    /** TV'nin bildirdiği sürüm; eski TV sürümleri bildirmez (null). */
    @Volatile private var tvVariant: String? = null

    private lateinit var status: TextView
    private lateinit var input: EditText
    private var ignoreText = false

    private var nsd: NsdManager? = null
    private var discovering = false
    private var multicast: WifiManager.MulticastLock? = null
    private val found = LinkedHashMap<String, Pair<String, Int>>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val prefs by lazy { getSharedPreferences("remote", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        takeShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    /** Başka uygulamadan "Paylaş → TV'ye gönder" ile gelen dosyalar. */
    private fun takeShare(i: Intent?) {
        i ?: return
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) {
            // başka tarayıcıdan paylaşılan bağlantı: TV'de aç
            val link = Regex("https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value ?: return
            pendingUrl = link
            if (connected) flushShare() else toast("TV'ye bağlanınca açılacak")
            return
        }
        pendingShare.addAll(uris)
        if (connected) flushShare() else toast("TV'ye bağlanınca gönderilecek")
    }

    private fun flushShare() {
        pendingUrl?.let { pendingUrl = null; send("U " + enc(it)); toast("TV'de açılıyor") }
        if (pendingShare.isEmpty()) return
        val list = pendingShare.toList()
        pendingShare.clear()
        sendUris(list)
    }

    override fun onStart() {
        super.onStart()
        destroyed = false
        val host = prefs.getString("host", null)
        if (host != null) connect(host, prefs.getInt("port", REMOTE_PORT), prefs.getString("name", host) ?: host)
        startDiscovery()
        main.postDelayed(ping, 3000)
    }

    override fun onStop() {
        super.onStop()
        destroyed = true
        main.removeCallbacksAndMessages(null)
        stopDiscovery()
        disconnect()
    }

    private val ping = object : Runnable {
        override fun run() {
            if (connected) send("P")
            main.postDelayed(this, 3000)
        }
    }

    // ------------------------------------------------------------------ arayüz

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF2C2E33.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply {
            text = "TV aranıyor…"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        val row0 = LinearLayout(this)
        row0.addView(btn("Dosya gönder") { pickFiles() })
        row0.addView(btn("TV'yi güncelle") { updateTv() })
        row0.addView(btn("Cihaz") { chooseDevice() })
        root.addView(row0, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })

        fun tv(label: String, cmd: String) = btn(label) {
            if (connected) send(cmd) else toast("TV'ye bağlı değil")
        }.apply { setTextColor(0xFFFFC83D.toInt()) }
        val rowTv = LinearLayout(this)
        rowTv.addView(tv("⏻ Güç", "PWR"))
        rowTv.addView(tv("TV ana ekran", "GH"))
        rowTv.addView(tv("Giriş", "IN?"))
        root.addView(rowTv, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        val rowApp = LinearLayout(this)
        rowApp.addView(tv("⇄ Önceki", "X"))
        rowApp.addView(tv("Uygulamalar", "X?"))
        rowApp.addView(tv("Tarayıcıyı aç", "W"))
        root.addView(rowApp, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })

        val pad = TouchpadView(this) { send(it) }
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(8), 0, dp(8)) })

        // gerçek kumanda gibi: solda yön tuşları, sağda işlev tuşları
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val pad5 = android.widget.FrameLayout(this)
        val b = dp(54)
        pad5.addView(round("▲", "K UP", true), android.widget.FrameLayout.LayoutParams(b, b, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pad5.addView(round("▼", "K DOWN", true), android.widget.FrameLayout.LayoutParams(b, b, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        pad5.addView(round("◀", "K LEFT", true), android.widget.FrameLayout.LayoutParams(b, b, Gravity.START or Gravity.CENTER_VERTICAL))
        pad5.addView(round("▶", "K RIGHT", true), android.widget.FrameLayout.LayoutParams(b, b, Gravity.END or Gravity.CENTER_VERTICAL))
        pad5.addView(round("OK", "K CENTER", false).apply { setTextColor(0xFFFFC83D.toInt()); textSize = 16f },
            android.widget.FrameLayout.LayoutParams(b, b, Gravity.CENTER))
        bottom.addView(pad5, LinearLayout.LayoutParams(dp(166), dp(166)))
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun sideRow(vararg v: View) {
            val r = LinearLayout(this)
            v.forEach { r.addView(it) }
            side.addView(r, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        sideRow(btn("Geri") { send("K BACK") }, btn("Menü") { send("K MENU") })
        sideRow(btn("Ana sayfa") { send("K HOME") }, btn("Yenile") { send("K REFRESH") })
        sideRow(btn("Adres") { addressDialog() }, btn("⌨ Klavye") { showKeyboard() })
        bottom.addView(side, LinearLayout.LayoutParams(0, dp(166), 1f).apply { leftMargin = dp(8) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        val row3 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Yazmak için dokunun"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9E9E9E.toInt())
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(0x26FFFFFF); setStroke(dp(1), 0x2EFFFFFF); cornerRadius = dp(22).toFloat() }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, id, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE || enter) {
                    send("K ENTER"); clearInput(); true
                } else false
            }
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && text.isEmpty()) {
                    send("B 1"); true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: Editable?) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    if (ignoreText) return
                    if (before > 0) send("B $before")
                    if (count > 0) send("T " + enc(s.subSequence(start, start + count).toString()))
                }
            })
        }
        row3.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row3.addView(btn("⌫", 0) { send("B 1"); removeLastLocal() })
        row3.addView(btn("↵", 0) { send("K ENTER"); clearInput() })
        root.addView(row3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return root
    }

    private fun btn(t: String, weight: Int = 1, onClick: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        includeFontPadding = false
        setBackgroundResource(R.drawable.tile_bg)
        isClickable = true
        setOnClickListener { buzz(); onClick() }
        if (weight > 0) {
            setPadding(dp(4), 0, dp(4), 0)
            if (Build.VERSION.SDK_INT >= 26)
                setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LinearLayout.LayoutParams(0, -1, weight.toFloat()).apply { setMargins(dp(3), 0, dp(3), 0) }
        } else {
            textSize = 18f
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(46)).apply { setMargins(dp(6), 0, 0, 0) }
        }
    }

    /** Yuvarlak kumanda tuşu; repeat = basılı tutunca tekrarlar. */
    private fun round(t: String, cmd: String, repeat: Boolean) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setBackgroundResource(R.drawable.round_bg)
        isClickable = true
        if (!repeat) setOnClickListener { buzz(); send(cmd) }
        else {
            val again = object : Runnable {
                override fun run() { send(cmd); main.postDelayed(this, 110) }
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; buzz(); send(cmd); main.postDelayed(again, 420) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; main.removeCallbacks(again) }
                }
                true
            }
        }
    }

    private fun clearInput() {
        ignoreText = true
        input.setText("")
        ignoreText = false
    }

    private fun removeLastLocal() {
        val t = input.text
        if (t.isNotEmpty()) { ignoreText = true; t.delete(t.length - 1, t.length); ignoreText = false }
    }

    private fun showKeyboard() {
        clearInput()
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun addressDialog() {
        val e = EditText(this).apply {
            hint = "Adres veya arama"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("TV'de aç").setView(e)
            .setPositiveButton("Aç") { _, _ -> if (e.text.isNotBlank()) send("U " + enc(e.text.toString())) }
            .setNegativeButton("Vazgeç", null).show()
        e.requestFocus()
        main.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun chooseDevice() {
        val names = found.keys.toList()
        val items = names.map { it.removePrefix("JetBrowser ") } + "IP adresi ile bağlan"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (names.isEmpty()) "TV bulunamadı" else "TV seçin")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < names.size) {
                    val (h, p) = found[names[i]]!!
                    connect(h, p, names[i])
                } else ipDialog()
            }.show()
    }

    private fun ipDialog() {
        val e = EditText(this).apply {
            hint = "örn. 192.168.1.40"
            setSingleLine()
            setText(prefs.getString("host", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("TV adresi (TV'de Ayarlar'da yazar)").setView(e)
            .setPositiveButton("Bağlan") { _, _ ->
                val raw = e.text.toString().trim()
                val host = raw.substringBefore(':')
                val port = raw.substringAfter(':', "").toIntOrNull() ?: REMOTE_PORT
                if (host.isNotEmpty()) connect(host, port, host)
            }.setNegativeButton("Vazgeç", null).show()
    }

    /** TV'den gelen liste (uygulamalar ya da girişler): seçileni TV'de açar. */
    private fun showTvList(line: String) {
        val kind = line.substring(2, 3)
        val items = line.substring(4).split('&').mapNotNull {
            val p = it.split('=')
            if (p.size == 2) Pair(java.net.URLDecoder.decode(p[0], "UTF-8"), p[1]) else null
        }
        if (items.isEmpty() || isFinishing) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (kind == "I") "TV girişi seçin" else "TV'de aç  (● açık, ↺ son kullanılan)")
            .setItems(items.map { it.first }.toTypedArray()) { _, i ->
                send((if (kind == "I") "IN " else "XO ") + items[i].second)
            }.show()
    }

    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(15, 60))
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ bağlantı

    private fun setStatus(s: String) { status.text = s }

    private fun connect(host: String, port: Int, name: String) {
        disconnect()
        connName = name
        setStatus("Bağlanıyor: " + name.removePrefix("JetBrowser "))
        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 3000)
                s.tcpNoDelay = true
                val w = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                w.println("H " + Build.MODEL)
                tvVariant = null
                socket = s
                writer = w
                curHost = host
                curPort = port
                prefs.edit().putString("host", host).putInt("port", port).putString("name", name).apply()
                main.post {
                    connected = true
                    setStatus("● Bağlı: " + name.removePrefix("JetBrowser "))
                    status.setTextColor(0xFF66BB6A.toInt())
                    flushShare()
                }
                Thread { readLoop(s) }.start()
            } catch (_: Exception) {
                main.post {
                    if (!destroyed) {
                        setStatus("TV bekleniyor… (TV'de JetBrowser açık olmalı)")
                        status.setTextColor(Color.WHITE)
                        // son bağlanılan TV açılana kadar kendiliğinden yeniden dene
                        main.postDelayed({
                            if (!connected && !destroyed && connName == name) connect(host, port, name)
                        }, 3000)
                    }
                }
            }
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = r.readLine() ?: break
                if (line == "F") main.post { showKeyboard() }
                else if (line.startsWith("L ")) main.post { showTvList(line) }
                else if (line.startsWith("V ")) {
                    val p = line.split(' ')
                    tvVariant = p.getOrNull(2)
                    main.post { if (connected) setStatus("● Bağlı: " + connName.removePrefix("JetBrowser ") + "  (1.0." + p.getOrNull(1) + ")") }
                }
                else if (line.startsWith("N ")) main.post { Toast.makeText(this, line.substring(2), Toast.LENGTH_LONG).show() }
            }
        } catch (_: Exception) { }
        main.post { if (socket === s) lost() }
    }

    private fun lost() {
        connected = false
        writer = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        if (destroyed) return
        setStatus("Bağlantı koptu, yeniden deneniyor…")
        status.setTextColor(0xFFFFB74D.toInt())
        main.postDelayed({
            val host = prefs.getString("host", null)
            if (!connected && !destroyed && host != null) connect(host, prefs.getInt("port", REMOTE_PORT), connName)
        }, 2000)
    }

    private fun disconnect() {
        connected = false
        val s = socket
        socket = null
        writer = null
        io.execute { try { s?.close() } catch (_: Exception) { } }
    }

    private fun send(cmd: String) {
        val w = writer ?: return
        io.execute {
            w.println(cmd)
            if (w.checkError()) main.post { if (writer === w) lost() }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ dosya gönderme

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun pickFiles() {
        if (curHost == null || !connected) { toast("Önce TV'ye bağlanın"); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 11)
        } catch (_: Exception) { toast("Dosya seçici açılamadı") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 11 || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        else data.data?.let { uris.add(it) }
        sendUris(uris)
    }

    private class Outgoing(val name: String, val size: Long, val open: () -> InputStream)

    private fun sendUris(uris: List<Uri>) {
        val items = uris.mapNotNull { u ->
            var name = u.lastPathSegment?.substringAfterLast('/') ?: "dosya"
            var size = -1L
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (_: Exception) { }
            if (size < 0) {
                // boyutu bilinmiyorsa önce geçici dosyaya kopyala
                try {
                    val tmp = File(cacheDir, "send_" + System.nanoTime())
                    contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val t = tmp
                    Outgoing(name, t.length()) { FileInputStream(t) }
                } catch (_: Exception) { null }
            } else Outgoing(name, size) { contentResolver.openInputStream(u) ?: throw java.io.IOException() }
        }
        sendItems(items)
    }

    /** Bu telefondaki JetBrowser'ı TV'ye gönderir; TV'de kurulum ekranı açılır. */
    /** TV'yi en son sürüme günceller. Yeni TV sürümleri kendisi indirir; eskilerine telefon indirip gönderir. */
    private fun updateTv() {
        if (!connected) { toast("Önce TV'ye bağlanın"); return }
        val b = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("TV'yi güncelle")
        if (tvVariant != null) {
            b.setMessage("TV en son sürümü kendisi indirecek. Kurulum ekranı açılınca \"Yükle\" deyin.")
                .setPositiveButton("Güncelle") { _, _ -> send("UPD") }
                .setNegativeButton("Vazgeç", null).show()
        } else {
            // eski TV sürümü: hangi tür kurulu olduğunu bildiremiyor
            b.setItems(arrayOf("Tam sürüm (tüm TV'de imleç ve klavye)", "Normal sürüm")) { _, i ->
                downloadAndSend(if (i == 0) "jet-full.apk" else "jet.apk")
            }.show()
        }
    }

    private fun downloadAndSend(name: String) {
        toast("En son sürüm indiriliyor…")
        Thread {
            val f = try {
                val c = java.net.URL("https://github.com/kuryuk77/jetbrowser/releases/latest/download/$name")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                if (c.responseCode != 200) throw java.io.IOException()
                val out = File(cacheDir, "send_update.apk")
                c.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
                out
            } catch (_: Exception) { null }
            main.post {
                if (f == null) toast("İndirilemedi. İnterneti kontrol edin")
                else sendItems(listOf(Outgoing("JetBrowser-guncelleme.apk", f.length()) { FileInputStream(f) }))
            }
        }.start()
    }

    private fun sendItems(items: List<Outgoing>) {
        val host = curHost
        if (host == null || items.isEmpty()) return
        if (sendingFiles) { toast("Bir gönderim zaten sürüyor"); return }
        sendingFiles = true
        val port = curPort
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val label = TextView(this).apply { setTextColor(Color.WHITE) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        box.addView(label); box.addView(bar)
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("TV'ye gönderiliyor").setView(box).setCancelable(false).show()
        Thread {
            var ok = 0
            for ((idx, it) in items.withIndex()) {
                main.post { label.text = "${idx + 1}/${items.size}  ${it.name}"; bar.progress = 0 }
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 4000)
                        s.soTimeout = 60000
                        val out = s.getOutputStream().buffered(64 * 1024)
                        out.write(("FILE " + enc(it.name) + " " + it.size + "\n").toByteArray())
                        var sent = 0L
                        var lastUi = 0L
                        it.open().use { ins ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                sent += n
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 150 && it.size > 0) {
                                    lastUi = now
                                    val p = (sent * 1000 / it.size).toInt()
                                    main.post { bar.progress = p }
                                }
                            }
                        }
                        out.flush()
                        val reply = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                        if (reply == "OK") ok++
                    }
                } catch (_: Exception) { }
            }
            main.post {
                sendingFiles = false
                try { dlg.dismiss() } catch (_: Exception) { }
                toast(if (ok == items.size) "Gönderildi ✓" else "$ok/${items.size} dosya gönderildi")
                cacheDir.listFiles()?.filter { it.name.startsWith("send_") }?.forEach { it.delete() }
            }
        }.start()
    }

    // ------------------------------------------------------------------ TV bulma (mDNS)

    private fun startDiscovery() {
        if (discovering) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wm.createMulticastLock("jet").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        val m = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        try {
            m.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            discovering = true
        } catch (_: Exception) { }
    }

    private fun stopDiscovery() {
        if (discovering) try { nsd?.stopServiceDiscovery(discovery) } catch (_: Exception) { }
        discovering = false
        try { multicast?.release() } catch (_: Exception) { }
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(t: String?, c: Int) { discovering = false }
        override fun onStopDiscoveryFailed(t: String?, c: Int) {}
        override fun onDiscoveryStarted(t: String?) {}
        override fun onDiscoveryStopped(t: String?) {}
        override fun onServiceLost(i: NsdServiceInfo?) {}
        override fun onServiceFound(i: NsdServiceInfo) {
            if (i.serviceName == "JetBrowser " + Build.MODEL) return
            main.post { resolveQueue.addLast(i); resolveNext() }
        }
    }

    private fun resolveNext() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, code: Int) {
                    main.post {
                        resolving = false
                        if (code == NsdManager.FAILURE_ALREADY_ACTIVE) resolveQueue.addLast(info)
                        main.postDelayed({ resolveNext() }, 300)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress
                    main.post {
                        resolving = false
                        if (host != null) onFound(i.serviceName, host, i.port)
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) { resolving = false }
    }

    private fun onFound(name: String, host: String, port: Int) {
        found[name] = Pair(host, port)
        if (connected) return
        val last = prefs.getString("name", null)
        if (last == null || last == name || last == host || found.size == 1) connect(host, port, name)
        else setStatus("${found.size} TV bulundu — \"Cihaz\" ile seçin")
    }
}

/** Parmak hareketlerini fare komutlarına çevirir. */
class TouchpadView(ctx: Context, private val out: (String) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val slop = 8 * d
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var scrolling = false
    private var scrollDone = false
    private var longPressed = false
    private var maxPointers = 1
    private val h = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        if (!moved && !scrolling) {
            longPressed = true
            out("LD")
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB5B8BE.toInt(); textSize = 14 * d; textAlign = Paint.Align.CENTER
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC83D.toInt(); textSize = 20 * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    init {
        background = GradientDrawable().apply {
            setColor(0x1AFFFFFF); cornerRadius = 24 * d; setStroke((1 * d).toInt(), 0x2EFFFFFF)
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawText("Touchpad", cx, cy - 30 * d, title)
        c.drawText("Kaydır: imleç   •   Dokun: tıkla", cx, cy, hint)
        c.drawText("İki parmak: sayfayı kaydır", cx, cy + 22 * d, hint)
        c.drawText("Basılı tut: uzun basma / sürükle", cx, cy + 44 * d, hint)
    }

    private fun f(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun avgX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun avgY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; downT = e.eventTime
                moved = false; scrolling = false; scrollDone = false; longPressed = false; maxPointers = 1
                h.postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                h.removeCallbacks(longPress)
                if (e.pointerCount == 2 && !scrolling && !longPressed) {
                    scrolling = true
                    lastX = avgX(e); lastY = avgY(e)
                    out("SD")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val ax = avgX(e); val ay = avgY(e)
                    val dx = (ax - lastX) / d; val dy = (ay - lastY) / d
                    if (abs(dx) + abs(dy) >= 0.5f) {
                        out("S ${f(dx)} ${f(dy)}")
                        lastX = ax; lastY = ay
                    }
                } else if (!scrollDone && e.pointerCount == 1) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; if (!longPressed) h.removeCallbacks(longPress) }
                    if (moved) {
                        val dx = (e.x - lastX) / d
                        val dy = (e.y - lastY) / d
                        val speed = hypot(dx, dy)
                        val acc = min(1.3f + speed * 0.12f, 3.5f)
                        if (speed > 0.2f) out("M ${f(dx * acc)} ${f(dy * acc)}")
                        lastX = e.x; lastY = e.y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (scrolling && e.pointerCount - 1 < 2) {
                    out("SE"); scrolling = false; scrollDone = true
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longPress)
                if (scrolling) { out("SE"); scrolling = false }
                if (longPressed) out("LU")
                else if (!moved && maxPointers == 1 && e.eventTime - downT < 350) {
                    out("C"); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(longPress)
                if (scrolling) out("SE")
                if (longPressed) out("LU")
                scrolling = false; longPressed = false
            }
        }
        return true
    }
}
