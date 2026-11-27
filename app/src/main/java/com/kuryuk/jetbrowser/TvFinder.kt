package com.kuryuk.jetbrowser

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** TV'deki JetBrowser'ı ağda arar (adresi değişmişse yenisini bulur). Arka planda çağırın. */
object TvFinder {
    fun find(ctx: Context, preferName: String?, timeoutMs: Long = 6000): Triple<String, Int, String>? {
        val app = ctx.applicationContext
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val lock = try {
            (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("jetfind").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) { null }
        val done = CountDownLatch(1)
        var result: Triple<String, Int, String>? = null
        var fallback: Triple<String, Int, String>? = null
        val own = "JetBrowser " + android.os.Build.MODEL
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val lockObj = Any()

        fun resolveNext() {
            val info = synchronized(lockObj) {
                if (resolving) return
                pending.removeFirstOrNull()?.also { resolving = true }
            } ?: return
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo?, code: Int) {
                        synchronized(lockObj) { resolving = false }
                        resolveNext()
                    }

                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        synchronized(lockObj) { resolving = false }
                        val host = i.host?.hostAddress
                        if (host != null) {
                            val t = Triple(host, i.port, i.serviceName)
                            if (preferName == null || i.serviceName == preferName) { result = t; done.countDown(); return }
                            if (fallback == null) fallback = t
                        }
                        resolveNext()
                    }
                })
            } catch (_: Exception) { synchronized(lockObj) { resolving = false } }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, c: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(t: String?, c: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(i: NsdServiceInfo?) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == own) return
                synchronized(lockObj) { pending.addLast(i) }
                resolveNext()
            }
        }
        try {
            nsd.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
            try { lock?.release() } catch (_: Exception) { }
        }
        return result ?: fallback
    }
}
