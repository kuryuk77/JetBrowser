package com.kuryuk.jetbrowser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/**
 * TV'de sistem genelinde kumanda: imleç + dokunma, uygulama geçişi, ana ekran, geri, uyutma.
 * Ok tuşları ve yazma için yalnızca odaklı öğeye erişir; hiçbir şeyi kaydetmez ya da göndermez.
 */
class JetAccess : AccessibilityService() {
    /** En son kullanılan uygulamalar, en yenisi başta. */
    val history = ArrayList<String>()
    private val skip = HashSet<String>()
    private val h = Handler(Looper.getMainLooper())
    private var cursorView: CursorView? = null
    private val hide = Runnable { cursorView?.visibility = View.GONE }
    private val d get() = resources.displayMetrics.density

    private var sx = 0f
    private var sy = 0f
    private var ax = 0f
    private var ay = 0f
    private var busy = false
    private var endPending = false

    override fun onServiceConnected() {
        instance = this
        Hub.start(this)
        skip.add("com.android.systemui")
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0).forEach { skip.add(it.activityInfo.packageName) }
        } catch (_: Exception) { }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (history.firstOrNull() == p || skip.contains(p)) return
        if (launchIntent(p) == null) { skip.add(p); return }
        history.remove(p)
        history.add(0, p)
        while (history.size > 20) history.removeAt(history.size - 1)
    }

    fun launchIntent(p: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)

    fun open(p: String): Boolean {
        // kendi uygulamamızda video oynuyorsa tarayıcıya değil oynatıcıya dön
        if (p == packageName) Hub.ownIntent(this)?.let { i ->
            return try { startActivity(i); true } catch (_: Exception) { false }
        }
        val i = launchIntent(p) ?: return false
        return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** Alt+Tab: bir önceki uygulamaya dön. */
    fun switchApp(): Boolean = history.getOrNull(1)?.let { open(it) } ?: false

    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun sleep() = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    // ------------------------------------------------------------------ sistem geneli imleç

    // İmleç küçük bir pencerede çizilir ve tıklanan noktanın hemen sağ altında durur.
    // Böylece Android'in güvenlik pencereleri (kurulum onayı vb.) "üstü kapalı" sayıp dokunmayı reddetmez.
    private var px = -1f
    private var py = -1f
    private var scrW = 1920f
    private var scrH = 1080f
    private var lp: WindowManager.LayoutParams? = null

    private fun cursor(): CursorView {
        h.removeCallbacks(hide)
        h.postDelayed(hide, 8000)
        cursorView?.let { it.visibility = View.VISIBLE; return it }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        scrW = size.x.toFloat(); scrH = size.y.toFloat()
        if (px < 0) { px = scrW / 2; py = scrH / 2 }
        val v = CursorView(this)
        v.cx = 0f; v.cy = 0f
        val box = (40 * d).toInt()
        val l = WindowManager.LayoutParams(
            box, box, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        lp = l
        place(l)
        try { wm.addView(v, l) } catch (_: Exception) { }
        cursorView = v
        return v
    }

    private fun place(l: WindowManager.LayoutParams? = lp) {
        l ?: return
        l.x = px.toInt() + 2
        l.y = py.toInt() + 2
        val v = cursorView ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(v, l) } catch (_: Exception) { }
    }

    fun hideCursor() { h.removeCallbacks(hide); cursorView?.visibility = View.GONE }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, done: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < 24) { done?.invoke(); return }
        val w = scrW - 1
        val hh = scrH - 1
        val p = Path().apply {
            moveTo(x1.coerceIn(0f, w), y1.coerceIn(0f, hh))
            if (x1 != x2 || y1 != y2) lineTo(x2.coerceIn(0f, w), y2.coerceIn(0f, hh))
        }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { done?.invoke() }
            override fun onCancelled(gd: GestureDescription?) { done?.invoke() }
        }, h)
        if (!ok) done?.invoke()
    }

    private fun flush() {
        if (busy || (ax == 0f && ay == 0f)) return
        busy = true
        val x1 = sx; val y1 = sy; val x2 = sx + ax; val y2 = sy + ay
        ax = 0f; ay = 0f
        gesture(x1, y1, x2, y2, 90) {
            busy = false
            if (endPending || hypot(ax, ay) > 60 * d) { endPending = false; flush() }
        }
    }

    /** Telefon touchpad komutları (JetBrowser ekranda değilken). */
    fun pointer(cmd: String, arg: String) {
        val v = cursor()
        fun xy(): Pair<Float, Float> {
            val p = arg.split(' ')
            return Pair(p.getOrNull(0)?.toFloatOrNull() ?: 0f, p.getOrNull(1)?.toFloatOrNull() ?: 0f)
        }
        when (cmd) {
            "M" -> {
                val (x, y) = xy()
                px = (px + x * d).coerceIn(0f, scrW - 1); py = (py + y * d).coerceIn(0f, scrH - 1)
                place()
            }
            "C" -> gesture(px, py, px, py, 60) { askKeyboardIfEditing() }
            "LD" -> gesture(px, py, px, py, 800)
            "SD" -> { sx = px; sy = py; ax = 0f; ay = 0f; endPending = false }
            "S" -> {
                val (x, y) = xy()
                ax += x * d * 1.5f; ay += y * d * 1.5f
                if (hypot(ax, ay) > 60 * d) flush()
            }
            "SE" -> { endPending = busy; flush() }
        }
    }

    // ------------------------------------------------------------------ ok tuşları ve klavye

    private fun focused(): AccessibilityNodeInfo? =
        try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }

    /** Odak bir yazı alanındaysa telefona "klavyeyi aç" de. */
    private fun askKeyboardIfEditing() {
        h.postDelayed({ if (focused()?.isEditable == true) Hub.server?.send("F") }, 500)
    }

    private fun firstFocusable(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (n.isFocusable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstFocusable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    /** Kumandanın ok / OK tuşları: odağı taşır ya da odaklı öğeye tıklar. */
    fun dpad(k: String): Boolean = try {
        val root = rootInActiveWindow
        val f = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (root == null) false
        else if (f == null) firstFocusable(root)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
        else if (k == "CENTER") {
            var n: AccessibilityNodeInfo? = f
            var hops = 0
            while (n != null && !n.isClickable && hops < 6) { n = n.parent; hops++ }
            val ok = (n ?: f).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            askKeyboardIfEditing()
            ok
        } else {
            val dir = when (k) {
                "UP" -> View.FOCUS_UP
                "DOWN" -> View.FOCUS_DOWN
                "LEFT" -> View.FOCUS_LEFT
                else -> View.FOCUS_RIGHT
            }
            f.focusSearch(dir)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
        }
    } catch (_: Exception) { false }

    private var pwNode: AccessibilityNodeInfo? = null
    private var pwBuf = ""

    /** Telefondan gelen yazıyı odaklı yazı alanına uygular (n = sondan silinecek karakter). */
    fun type(t: String, n: Int): Boolean = try {
        val f = focused()
        if (f == null || !f.isEditable) false
        else {
            var cur: String
            if (f.isPassword) {
                if (f != pwNode) { pwNode = f; pwBuf = "" }
                cur = pwBuf
            } else {
                cur = if (Build.VERSION.SDK_INT >= 26 && f.isShowingHintText) "" else f.text?.toString() ?: ""
            }
            if (n > 0) cur = cur.dropLast(n)
            cur += t
            if (f.isPassword) pwBuf = cur
            f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur)
            })
        }
    } catch (_: Exception) { false }

    fun enter(): Boolean = try {
        val f = focused()
        if (f != null && Build.VERSION.SDK_INT >= 30)
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        else false
    } catch (_: Exception) { false }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        try { cursorView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: JetAccess? = null
    }
}
