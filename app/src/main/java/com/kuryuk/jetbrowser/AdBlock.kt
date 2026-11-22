package com.kuryuk.jetbrowser

import android.net.Uri

/** Hafif reklam/izleyici engelleyici: bilinen alan adlarına giden istekleri boş döndürür. */
object AdBlock {
    private val hosts = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "advertising.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net", "onclkds.com",
        "adsterra.com", "adsterratools.com", "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net",
        "hilltopads.net", "clickadu.com", "adcash.com", "mgid.com", "revcontent.com", "zedo.com",
        "yllix.com", "a-ads.com", "coinhive.com", "admaven.com", "ad-maven.com", "highperformanceformat.com",
        "profitableratecpm.com", "realsrv.com", "tsyndicate.com", "bidgear.com", "adskeeper.com", "monetag.com",
        "pushwhy.com", "notifpush.com", "richpush.co", "evadav.com", "galaksion.com", "rollerads.com",
        "scorecardresearch.com", "quantserve.com", "moatads.com", "amazon-adsystem.com", "smartadserver.com",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "yieldmo.com", "media.net",
        "histats.com", "yandexadexchange.net", "an.yandex.ru", "mc.yandex.ru"
    )

    fun isAd(url: Uri?): Boolean {
        var h = url?.host?.lowercase() ?: return false
        while (true) {
            if (hosts.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }
}
