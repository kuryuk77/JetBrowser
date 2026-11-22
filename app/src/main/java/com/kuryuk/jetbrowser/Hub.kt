package com.kuryuk.jetbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
        server?.send("N TV'de izin gerekli: JetBrowser > Kumanda > Tam kontrol izni")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun handle(line: String) {
        val acc = JetAccess.instance
        val cmd = line.substringBefore(' ')
        val arg = line.substringAfter(' ', "")
        when (cmd) {
            "W" -> launch()
            "X" -> if (acc == null) needAccess() else if (!acc.switchApp()) server?.send("N Geçilecek önceki uygulama yok")
            "X?" -> sendApps()
            "XO" -> openApp(URLDecoder.decode(arg, "UTF-8"))
            "IN?" -> sendInputs()
            "IN" -> openInput(URLDecoder.decode(arg, "UTF-8"))
            "GH" -> if (acc == null) needAccess() else acc.home()
            "PWR" -> power()
            "M", "C", "LD", "LU", "SD", "S", "SE" ->
                if (!foreground && acc != null) acc.pointer(cmd, arg) else onCommand?.invoke(line)
            "K" -> if (arg == "BACK" && !foreground && acc != null) acc.back() else onCommand?.invoke(line)
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
        val recent = (JetAccess.instance?.history ?: emptyList<String>()).filter { labels.containsKey(it) }
        val rest = labels.keys.filter { !recent.contains(it) }.sortedBy { labels[it]!!.lowercase() }
        val out = recent.mapIndexed { i, p -> enc((if (i == 0) "● " else "↺ ") + labels[p]) + "=" + enc(p) } +
            rest.map { enc(labels[it]!!) + "=" + enc(it) }
        server?.send("L A " + out.joinToString("&"))
    }

    private fun startFromAnywhere(i: Intent): Boolean {
        val acc = JetAccess.instance
        if (acc == null && !foreground) { needAccess(); return false }
        return try { (acc ?: app)?.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
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

    /** Ekran kapalıysa uyandırır, açıksa uyutur. */
    private fun power() {
        val a = app ?: return
        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            val acc = JetAccess.instance
            if (acc == null) needAccess() else if (!acc.sleep()) server?.send("N Bu TV uzaktan kapatmayı desteklemiyor")
        } else try {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE, "jet:wake").acquire(3000)
        } catch (_: Exception) { }
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
