package com.kuryuk.jetbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/** Telefon kumandası bağlıyken TV'nin ekran klavyesini açmayan WebView. */
@SuppressLint("ViewConstructor")
class JetWebView(ctx: Context, val incognito: Boolean) : WebView(ctx) {
    var noIme = false
    var pending: String? = null

    override fun onCheckIsTextEditor(): Boolean = if (noIme) false else super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (noIme) null else super.onCreateInputConnection(outAttrs)
}
