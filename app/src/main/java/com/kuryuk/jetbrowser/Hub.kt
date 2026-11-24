package com.kuryuk.jetbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/** Kumanda sunucusunu uygulama süreci boyunca tek yerde tutar; ekran kapalıyken de dinleyebilir. */
object Hub {
    private var app: Context? = null
    var server: RemoteServer? = null
        private set
    var owner: Any? = null
    var onCommand: ((String) -> Unit)? = null
    var onClients: ((Int, String) -> Unit)? = null
    var onFile: ((Int, String, File?) -> Unit)? = null
    var clientCount = 0
        private set
    var clientName = ""
        private set
    var pendingFile: File? = null

    fun start(ctx: Context): RemoteServer {
        server?.let { return it }
        val a = ctx.applicationContext
        app = a
        val s = RemoteServer(a, { cmd -> handle(cmd) }, { n, name ->
            if (n > clientCount || n > 0) {
                @Suppress("DEPRECATION")
                val build = try { a.packageManager.getPackageInfo(a.packageName, 0).versionCode } catch (_: Exception) { 0 }
                server?.send("V $build " + (if (hasAccessBuild(a)) "full" else "lite") + " " + (WakeOnLan.ownMac() ?: "-"))
            }
            clientCount = n; clientName = name
            onClients?.invoke(n, name)
        })
        s.fileListener = { ev, name, file ->
            val l = onFile
            if (l != null) l(ev, name, file)
            else if (ev == 1 && file != null) { pendingFile = file; launch() }
        }
        s.start()
        server = s
        return s
    }

    /** JetBrowser ekranda mı? (MainActivity bildirir) */
    var foreground = false
        set(v) { field = v; if (v) JetAccess.instance?.hideCursor() }

    private fun needAccess() {
        val a = app
        server?.send(if (a != null && hasAccessBuild(a)) "N TV'de izin kapalı: TV Ayarları > Erişilebilirlik > JetBrowser'ı açın"
            else "N TV'de izin gerekli: JetBrowser > Kumanda > İzinler")
    }

    /** Bu kurulumda erişilebilirlik servisi var mı? (tam sürüm) */
    fun hasAccessBuild(ctx: Context) = try {
        ctx.packageManager.getServiceInfo(ComponentName(ctx, JetAccess::class.java), 0); true
    } catch (_: Exception) { false }

    /** Arka plandan uygulama açabilir miyiz? */
    fun canStart(ctx: Context) = JetAccess.instance != null || foreground ||
        Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)

    private fun homePackages(ctx: Context): Set<String> = try {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet() + "com.android.systemui"
    } catch (_: Exception) { setOf("com.android.systemui") }

    /** Son kullanılan uygulamalar (en yenisi başta): erişilebilirlik varsa oradan, yoksa kullanım erişiminden. */
    private fun recentApps(ctx: Context): List<String> {
        JetAccess.instance?.let { return it.history.toList() }
        return try {
            val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val skip = homePackages(ctx)
            val pm = ctx.packageManager
            um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3 * 86400000L, now)
                .filter { it.lastTimeUsed > 0 && !skip.contains(it.packageName) }
                .groupBy { it.packageName }.map { e -> Pair(e.key, e.value.maxOf { it.lastTimeUsed }) }
                .sortedByDescending { it.second }.map { it.first }
                .filter { (pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it)) != null }
                .take(20)
        } catch (_: Exception) { emptyList() }
    }

    private fun switchApp() {
        val a = app ?: return
        val acc = JetAccess.instance
        if (acc != null) { if (!acc.switchApp()) server?.send("N Geçilecek önceki uygulama yok"); return }
        val r = recentApps(a)
        if (r.isEmpty()) {
            server?.send(if (hasAccessBuild(a)) "N TV'de izin kapalı: TV Ayarları > Erişilebilirlik > JetBrowser'ı açın"
                else "N TV'de izin gerekli: TV Ayarları > Uygulamalar > Özel uygulama erişimi > Kullanım erişimi > JetBrowser")
            return
        }
        // ekrandaki uygulama listenin başındadır; JetBrowser ekrandaysa kendisi sayılır
        val current = if (foreground) a.packageName else r[0]
        val target = r.firstOrNull { it != current }
        if (target == null) server?.send("N Geçilecek önceki uygulama yok") else openApp(target)
    }

    private var lastHint = 0L
    /** Aynı uyarıyı sık sık göndermemek için. */
    private fun hint(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastHint > 8000) { lastHint = now; server?.send("N $msg") }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun handle(line: String) {
        val acc = JetAccess.instance
        val cmd = line.substringBefore(' ')
        val arg = line.substringAfter(' ', "")
        player?.let { if (it(line)) return }
        when (cmd) {
            "VID" -> playVideo(arg)
            "W" -> launch()
            "X" -> switchApp()
            "UPD" -> selfUpdate()
            "U" -> if (foreground && onCommand != null) onCommand?.invoke(line)
                else {
                    // JetBrowser ekranda değil: sayfayla birlikte öne getir
                    val a = app
                    if (a != null) startFromAnywhere(Intent(a, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(URLDecoder.decode(arg, "UTF-8"))))
                }
            "X?" -> sendApps()
            "XO" -> openApp(URLDecoder.decode(arg, "UTF-8"))
            "IN?" -> sendInputs()
            "IN" -> openInput(URLDecoder.decode(arg, "UTF-8"))
            "GH" -> if (acc != null) acc.home()
                else startFromAnywhere(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            "PWR" -> power()
            "M", "C", "LD", "LU", "SD", "S", "SE" ->
                if (!foreground && acc != null) acc.pointer(cmd, arg) else onCommand?.invoke(line)
            "T" -> if (!foreground && acc != null) { if (!acc.type(URLDecoder.decode(arg, "UTF-8"), 0)) hint("Önce TV'de bir yazı alanı seçin") }
                else onCommand?.invoke(line)
            "B" -> if (!foreground && acc != null) acc.type("", arg.toIntOrNull() ?: 1) else onCommand?.invoke(line)
            "K" -> if (!foreground && acc != null) when (arg) {
                "BACK" -> acc.back()
                "UP", "DOWN", "LEFT", "RIGHT", "CENTER" ->
                    if (!acc.dpad(arg)) hint("Bu ekranda ok tuşları çalışmıyor, touchpad'i deneyin")
                "ENTER" -> acc.enter()
                else -> onCommand?.invoke(line)
            } else onCommand?.invoke(line)
            else -> onCommand?.invoke(line)
        }
    }

    /** TV'deki uygulamalar: son kullanılanlar başta, sonra diğerleri. */
    private fun sendApps() {
        val a = app ?: return
        val pm = a.packageManager
        val labels = LinkedHashMap<String, String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach {
                    val p = it.activityInfo.packageName
                    if (!labels.containsKey(p)) labels[p] = it.loadLabel(pm).toString()
                }
            } catch (_: Exception) { }
        }
        val recent = recentApps(a).filter { labels.containsKey(it) }
        val rest = labels.keys.filter { !recent.contains(it) }.sortedBy { labels[it]!!.lowercase() }
        val out = recent.mapIndexed { i, p -> enc((if (i == 0) "● " else "↺ ") + labels[p]) + "=" + enc(p) } +
            rest.map { enc(labels[it]!!) + "=" + enc(it) }
        server?.send("L A " + out.joinToString("&"))
    }

    /** Video çalarken telefon komutlarını oynatıcıya yönlendirir. */
    var player: ((String) -> Boolean)? = null

    /** "VID <url> <tür> <başlıklar>" — videoyu TV oynatıcısında açar. */
    private fun playVideo(arg: String) {
        val a = app ?: return
        wakeScreen()
        val p = arg.split(' ')
        val url = URLDecoder.decode(p.getOrNull(0) ?: return, "UTF-8")
        // telefondaki altyazı görünümü ayarını TV'ye de uygula
        p.getOrNull(5)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.let { v ->
            val st = Store(a); st.subColor = v[0]; st.subSize = v[1]; st.subPos = v[2]; st.subBg = v[3]
        }
        val i = Intent(a, PlayerActivity::class.java)
            .putExtra("url", url)
            .putExtra("type", p.getOrNull(1) ?: "")
            .putExtra("headers", URLDecoder.decode(p.getOrNull(2) ?: "", "UTF-8"))
            .putExtra("pos", (p.getOrNull(3)?.toLongOrNull() ?: 0L) * 1000)
            .putExtra("subsFile", File(a.cacheDir, "subs.json").apply {
                writeText(URLDecoder.decode(p.getOrNull(4) ?: "", "UTF-8"))
            }.path)
        // oynatıcı zaten açıksa yeni videoyu doğrudan ona ver
        val open = PlayerActivity.instance
        if (open != null && !open.isFinishing) {
            open.runOnUiThread { open.play(i) }
            startFromAnywhere(Intent(a, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            return
        }
        startFromAnywhere(i)
    }

    private fun startFromAnywhere(i: Intent): Boolean {
        val acc = JetAccess.instance
        val a = app ?: return false
        if (!canStart(a)) { needAccess(); return false }
        return try { (acc ?: a).startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        catch (_: Exception) { server?.send("N Açılamadı"); false }
    }

    private fun openApp(p: String) {
        val pm = app?.packageManager ?: return
        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p)
        if (i == null) server?.send("N Açılamadı") else startFromAnywhere(i)
    }

    /** HDMI / anten gibi TV girişleri. */
    private fun sendInputs() {
        val a = app ?: return
        try {
            val tm = a.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            val list = tm.tvInputList.filter { it.isPassthroughInput }
            if (list.isEmpty()) { server?.send("N Bu TV giriş listesini uygulamalara vermiyor"); return }
            server?.send("L I " + list.joinToString("&") { enc(it.loadLabel(a).toString()) + "=" + enc(it.id) })
        } catch (_: Exception) { server?.send("N Bu TV giriş listesini uygulamalara vermiyor") }
    }

    private fun openInput(id: String) {
        startFromAnywhere(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)))
    }

    private const val RELEASES = "https://github.com/kuryuk77/jetbrowser/releases/latest"

    /** En son yayımlanan sürümün yapı numarası (1.0.N'deki N). Ağ çağrısı yapar; arka planda çağırın. */
    fun latestBuild(): Int? = try {
        val c = java.net.URL(RELEASES).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = "HEAD"
        c.connectTimeout = 10000; c.readTimeout = 10000
        val loc = c.getHeaderField("Location") ?: ""
        c.disconnect()
        Regex("/tag/v\\d+\\.\\d+\\.(\\d+)").find(loc)?.groupValues?.get(1)?.toInt()
    } catch (_: Exception) { null }

    /** Bu kurulumun türüne (normal / tam) uyan en son APK'yı indirir. Arka planda çağırın. */
    fun downloadLatest(ctx: Context): File? = try {
        val name = if (hasAccessBuild(ctx)) "jet-full.apk" else "jet.apk"
        val c = java.net.URL("$RELEASES/download/$name").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw java.io.IOException("http " + c.responseCode)
        val out = File(FileShare.dir(ctx), "JetBrowser-guncelleme.apk")
        c.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
        out
    } catch (_: Exception) { null }

    /** TV kendi sürümünün (normal ya da tam) en yenisini indirir ve kurulum ekranını açar. */
    @Volatile private var updating = false

    private fun toastTv(msg: String) {
        val a = app ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun selfUpdate() {
        val a = app ?: return
        if (updating) { server?.send("N Güncelleme zaten indiriliyor…"); return }
        updating = true
        wakeScreen()
        server?.send("N TV en son sürümü indiriyor…")
        toastTv("JetBrowser güncellemesi indiriliyor…")
        Thread {
            val done = downloadLatest(a) ?: run { Thread.sleep(1500); downloadLatest(a) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updating = false
                if (done == null) { server?.send("N TV güncellemeyi indiremedi"); toastTv("Güncelleme indirilemedi"); return@post }
                server?.send("N İndirildi. TV'de \"Yükle\"ye basın")
                // kurulum ekranını doğrudan aç; izin yoksa JetBrowser'a bırak (o izni sorar)
                val canInstall = Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls()
                @Suppress("DEPRECATION")
                val direct = canInstall && startFromAnywhere(Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(FileShare.uri(a, done), FileShare.mime(done.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                if (!direct) {
                    val l = onFile
                    if (l != null && foreground) l(1, done.name, done) else { pendingFile = done; launch() }
                }
            }
        }.start()
    }

    /** Ekran kapalıysa (bekleme) açar. */
    fun wakeScreen() {
        val a = app ?: return
        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        try {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE, "jet:wake").acquire(5000)
        } catch (_: Exception) { }
    }

    /** Ekran kapalıysa uyandırır, açıksa uyutur. */
    private fun power() {
        val a = app ?: return
        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            val acc = JetAccess.instance
            if (acc == null) server?.send("N TV'yi kapatma bu kurulumda yok (açma çalışır)") else if (!acc.sleep()) server?.send("N Bu TV uzaktan kapatmayı desteklemiyor")
        } else {
            wakeScreen()
            launch()   // ekranı açan bir pencere de göster (bazı TV'ler yalnızca böyle uyanır)
        }
    }

    fun stop() {
        server?.stop()
        server = null
        clientCount = 0
    }

    /** JetBrowser'ı öne getirir (TV'de uygulama kapalıyken telefondan açmak için). */
    fun launch() {
        val a: Context = JetAccess.instance ?: app ?: return
        try {
            a.startActivity(Intent(a, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("wake", true))
        } catch (_: Exception) { }
    }

    fun isTv(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun startService(ctx: Context) {
        try {
            val i = Intent(ctx, JetService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Exception) { }
    }
}

/** TV'de arka planda telefonu bekleyen servis. */
class JetService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.start(this)
        try {
            val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel("remote", "Telefon kumandası", NotificationManager.IMPORTANCE_MIN))
                Notification.Builder(this, "remote")
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.setContentTitle("JetBrowser").setContentText("Telefon kumandası bekleniyor")
                .setSmallIcon(R.drawable.ic_mouse).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
}

/** TV açılınca servisi başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (Hub.isTv(ctx)) Hub.startService(ctx)
    }
}
