package com.kuryuk.jetbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import java.util.Collections

/** Sayfada yakalanan video akışı (TV'de oynatmak için). */
data class Media(val url: String, val type: String, val headers: Map<String, String>)

/** Telefon kumandası bağlıyken TV'nin ekran klavyesini açmayan WebView. */
@SuppressLint("ViewConstructor")
class JetWebView(ctx: Context, val incognito: Boolean) : WebView(ctx) {
    var noIme = false
    var pending: String? = null
    @Volatile var pageUrl: String? = null
    /** Bu sayfada görülen video akışları, en eskisi başta. */
    val media: MutableList<Media> = Collections.synchronizedList(mutableListOf())

    override fun onCheckIsTextEditor(): Boolean = if (noIme) false else super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (noIme) null else super.onCreateInputConnection(outAttrs)
}
