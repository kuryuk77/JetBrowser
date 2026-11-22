package com.kuryuk.jetbrowser

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

const val REMOTE_PORT = 8765
const val NSD_TYPE = "_jetbrowser._tcp."

/** TV tarafı: telefondan gelen kumanda komutlarını dinler (satır tabanlı TCP). */
class RemoteServer(
    private val ctx: Context,
    private val onCommand: (String) -> Unit,
    private val onClients: (Int, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()
    private val sender = Executors.newSingleThreadExecutor()
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    @Volatile private var running = false
    val serviceName = "JetBrowser " + Build.MODEL

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val ss = try { ServerSocket(REMOTE_PORT) } catch (e: Exception) { ServerSocket(0) }
                ss.reuseAddress = true
                server = ss
                main.post { register(ss.localPort) }
                while (running) {
                    val s = ss.accept()
                    Thread { serve(s) }.start()
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun serve(s: Socket) {
        var out: PrintWriter? = null
        var name = "Telefon"
        try {
            s.tcpNoDelay = true
            out = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
            clients.add(out)
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running) {
                val line = r.readLine() ?: break
                if (line.startsWith("H ")) {
                    name = line.substring(2)
                    val n = clients.size
                    main.post { onClients(n, name) }
                }
                if (line != "P") main.post { onCommand(line) }
            }
        } catch (_: Exception) {
        } finally {
            out?.let { clients.remove(it) }
            try { s.close() } catch (_: Exception) { }
            val n = clients.size
            main.post { onClients(n, name) }
        }
    }

    /** Bağlı telefonlara mesaj gönder (ör. "F" = klavyeyi aç). */
    fun send(msg: String) {
        sender.execute { clients.forEach { try { it.println(msg) } catch (_: Exception) { } } }
    }

    private fun register(port: Int) {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = this@RemoteServer.serviceName
                serviceType = NSD_TYPE
                setPort(port)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(i: NsdServiceInfo?, c: Int) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo?, c: Int) {}
                override fun onServiceRegistered(i: NsdServiceInfo?) {}
                override fun onServiceUnregistered(i: NsdServiceInfo?) {}
            }
            nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            reg = l
        } catch (_: Exception) { }
    }

    val port: Int get() = server?.localPort ?: REMOTE_PORT

    fun stop() {
        running = false
        try { reg?.let { nsd?.unregisterService(it) } } catch (_: Exception) { }
        reg = null
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    companion object {
        fun localIp(): String {
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: continue
                    }
                }
            } catch (_: Exception) { }
            return "?"
        }
    }
}
