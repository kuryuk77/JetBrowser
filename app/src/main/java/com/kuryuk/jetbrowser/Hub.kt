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
import android.os.Build
import android.os.IBinder
import java.io.File

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
        val s = RemoteServer(a, { cmd ->
            if (cmd == "W") launch()
            onCommand?.invoke(cmd)
        }, { n, name ->
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

    fun stop() {
        server?.stop()
        server = null
        clientCount = 0
    }

    /** JetBrowser'ı öne getirir (TV'de uygulama kapalıyken telefondan açmak için). */
    fun launch() {
        val a = app ?: return
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
