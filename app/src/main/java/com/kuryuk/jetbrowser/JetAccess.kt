package com.kuryuk.jetbrowser

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/** TV'de sistem genelinde kumanda: uygulama geçişi, ana ekran, geri, uyutma. Ekran içeriğini okumaz. */
class JetAccess : AccessibilityService() {
    private var cur: String? = null
    private var prev: String? = null
    private val skip = HashSet<String>()

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
        if (p == cur || skip.contains(p)) return
        if (launchIntent(p) == null) { skip.add(p); return }
        prev = cur
        cur = p
    }

    private fun launchIntent(p: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)

    /** Alt+Tab: bir önceki uygulamaya dön. */
    fun switchApp(): Boolean {
        val p = prev ?: return false
        val i = launchIntent(p) ?: return false
        return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun sleep() = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: JetAccess? = null
    }
}
