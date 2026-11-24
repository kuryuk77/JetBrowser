package com.kuryuk.jetbrowser

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/** Bekleme modundaki TV'yi ağ üzerinden uyandırma (Wake-on-LAN). */
object WakeOnLan {
    fun valid(mac: String?): Boolean =
        mac != null && Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(mac.trim()) &&
            !mac.trim().startsWith("02:00:00") && mac.trim() != "00:00:00:00:00:00"

    fun normalize(mac: String) = mac.trim().replace('-', ':').uppercase()

    /** Sihirli paketi yayın adresine birkaç kez gönderir. Arka planda çağırın. */
    fun send(mac: String, tvIp: String?) {
        val b = normalize(mac).split(':').map { it.toInt(16).toByte() }.toByteArray()
        val data = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) data[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(b, 0, data, 6 + i * 6, 6)
        val targets = mutableListOf("255.255.255.255")
        tvIp?.split('.')?.takeIf { it.size == 4 }?.let { targets.add(it.take(3).joinToString(".") + ".255"); targets.add(tvIp) }
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                repeat(3) {
                    for (t in targets) for (port in intArrayOf(9, 7)) {
                        try { s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), port)) } catch (_: Exception) { }
                    }
                    Thread.sleep(150)
                }
            }
        } catch (_: Exception) { }
    }

    /** TV'nin kendi MAC adresi (yeni Android sürümleri gizleyebilir; o zaman null). */
    fun ownMac(): String? {
        for (n in listOf("eth0", "wlan0")) {
            try {
                val hw = NetworkInterface.getByName(n)?.hardwareAddress
                if (hw != null && hw.size == 6) {
                    val m = hw.joinToString(":") { String.format("%02X", it) }
                    if (valid(m)) return m
                }
            } catch (_: Exception) { }
            try {
                val m = File("/sys/class/net/$n/address").readText().trim().uppercase()
                if (valid(m)) return m
            } catch (_: Exception) { }
        }
        return null
    }
}
