package com.kuryuk.jetbrowser

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
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
    /** Dosya olayları: 0 = başladı, 1 = bitti, 2 = hata */
    var fileListener: ((Int, String, File?) -> Unit)? = null
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

    private fun readLineRaw(ins: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8_000_000) return null
        }
    }

    private fun receiveFile(header: String, ins: InputStream, s: Socket) {
        val p = header.split(' ')
        val name = URLDecoder.decode(p.getOrNull(1) ?: "dosya", "UTF-8")
        val size = p.getOrNull(2)?.toLongOrNull() ?: -1L
        val f = FileShare.target(ctx, name)
        main.post { fileListener?.invoke(0, f.name, null) }
        try {
            FileOutputStream(f).use { out ->
                val buf = ByteArray(64 * 1024)
                var left = size
                while (left != 0L) {
                    val want = if (left < 0) buf.size.toLong() else minOf(left, buf.size.toLong())
                    val n = ins.read(buf, 0, want.toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (left > 0) left -= n
                }
                if (left > 0) throw java.io.IOException("eksik")
            }
            s.getOutputStream().write("OK\n".toByteArray())
            s.getOutputStream().flush()
            main.post { fileListener?.invoke(1, f.name, f) }
        } catch (e: Exception) {
            f.delete()
            main.post { fileListener?.invoke(2, f.name, null) }
        } finally {
            try { s.close() } catch (_: Exception) { }
        }
    }

    private fun serve(s: Socket) {
        var out: PrintWriter? = null
        var name = "Telefon"
        var counted = false
        try {
            s.tcpNoDelay = true
            val ins = BufferedInputStream(s.getInputStream())
            val first = readLineRaw(ins) ?: return
            if (first.startsWith("FILE ")) { receiveFile(first, ins, s); return }
            out = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
            clients.add(out)
            counted = true
            val r = BufferedReader(InputStreamReader(ins, Charsets.UTF_8))
            var line: String? = first
            while (running && line != null) {
                val l: String = line
                if (l.startsWith("H ")) {
                    name = l.substring(2)
                    val n = clients.size
                    main.post { onClients(n, name) }
                }
                if (l != "P") main.post { onCommand(l) }
                line = r.readLine()
            }
        } catch (_: Exception) {
        } finally {
            out?.let { clients.remove(it) }
            try { s.close() } catch (_: Exception) { }
            if (counted) {
                val n = clients.size
                main.post { onClients(n, name) }
            }
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
