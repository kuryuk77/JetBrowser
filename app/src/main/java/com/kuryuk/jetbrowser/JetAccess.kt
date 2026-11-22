package com.kuryuk.jetbrowser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/**
 * TV'de sistem genelinde kumanda: imleç + dokunma, uygulama geçişi, ana ekran, geri, uyutma.
 * Ekran içeriğini okumaz.
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
        val i = launchIntent(p) ?: return false
        return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** Alt+Tab: bir önceki uygulamaya dön. */
    fun switchApp(): Boolean = history.getOrNull(1)?.let { open(it) } ?: false

    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun sleep() = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    // ------------------------------------------------------------------ sistem geneli imleç

    private fun cursor(): CursorView {
        h.removeCallbacks(hide)
        h.postDelayed(hide, 8000)
        cursorView?.let { it.visibility = View.VISIBLE; return it }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        val v = CursorView(this)
        v.cx = size.x / 2f
        v.cy = size.y / 2f
        val lp = WindowManager.LayoutParams(
            size.x, size.y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        v.maxW = size.x.toFloat()
        v.maxH = size.y.toFloat()
        try { wm.addView(v, lp) } catch (_: Exception) { }
        cursorView = v
        return v
    }

    fun hideCursor() { h.removeCallbacks(hide); cursorView?.visibility = View.GONE }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, done: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < 24) { done?.invoke(); return }
        val v = cursorView
        val w = (v?.maxW ?: 1920f) - 1
        val hh = (v?.maxH ?: 1080f) - 1
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
            "M" -> { val (x, y) = xy(); v.moveBy(x * d, y * d) }
            "C" -> gesture(v.cx, v.cy, v.cx, v.cy, 60)
            "LD" -> gesture(v.cx, v.cy, v.cx, v.cy, 800)
            "SD" -> { sx = v.cx; sy = v.cy; ax = 0f; ay = 0f; endPending = false }
            "S" -> {
                val (x, y) = xy()
                ax += x * d * 1.5f; ay += y * d * 1.5f
                if (hypot(ax, ay) > 60 * d) flush()
            }
            "SE" -> { endPending = busy; flush() }
        }
    }

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
