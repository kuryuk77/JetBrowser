package com.kuryuk.jetbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import java.util.Collections

/** Sayfada yakalanan video akışı (TV'de oynatmak için). */
/** Sayfada yakalanan altyazı dosyası. */
/** text: oynatıcıdan doğrudan okunan altyazı içeriği (dosya adresi yoksa) */
data class Sub(val url: String, val label: String, val headers: Map<String, String>, val text: String? = null)

/** duration: saniye, bilinmiyorsa -1 */
data class Media(val url: String, val type: String, val headers: Map<String, String>,
                 val master: Boolean = false, val duration: Double = -1.0)

/** Telefon kumandası bağlıyken TV'nin ekran klavyesini açmayan WebView. */
@SuppressLint("ViewConstructor")
class JetWebView(ctx: Context, val incognito: Boolean) : WebView(ctx) {
    var noIme = false
    var pending: String? = null
    @Volatile var pageUrl: String? = null
    /** Menüdeki yakınlaştırma yüzdesi */
    var zoomPct = 100
    /** Sayfadaki videonun son oynatma konumu (sn). */
    @Volatile var videoTime = 0.0
    /** Bu sayfada görülen video akışları, en eskisi başta. */
    val media: MutableList<Media> = Collections.synchronizedList(mutableListOf())
    val subs: MutableList<Sub> = Collections.synchronizedList(mutableListOf())

    override fun onCheckIsTextEditor(): Boolean = if (noIme) false else super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (noIme) null else super.onCreateInputConnection(outAttrs)
}
