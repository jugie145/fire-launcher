package de.jugie.firelauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Oeffnet den eigenen Startbildschirm, sobald Amazons Startbildschirm erscheint.
 * Die Fire-TV-Einstellungen gehoeren zum selben Amazon-Paket und bleiben erreichbar.
 */
class HomeService : AccessibilityService() {

    companion object {
        /** Bis zu diesem Zeitpunkt (uptimeMillis) nicht umleiten, z. B. wenn der Launcher die Einstellungen oeffnet. */
        @Volatile
        var settingsUntil = 0L
    }

    private val amazon = setOf("com.amazon.tv.launcher", "com.amazon.firelauncher")
    private val handler = Handler(Looper.getMainLooper())
    private var cover: View? = null
    private var pending = false

    private val go = Runnable {
        pending = false
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            uncover()
        }
    }
    private val uncoverLater = Runnable { uncover() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        val c = event.className?.toString() ?: ""

        if (p == packageName) {
            if (c.contains("Activity")) uncover()
            return
        }
        if (c.contains("Settings")) {
            // Einstellungen wurden geoeffnet: geplante Umleitung abbrechen
            if (pending) {
                pending = false
                handler.removeCallbacks(go)
            }
            uncover()
            return
        }
        if (amazon.contains(p) && c.contains("HomeActivity")) {
            if (!Prefs.redirect(this)) return
            if (SystemClock.uptimeMillis() < settingsUntil) return
            showCover()
            pending = true
            handler.removeCallbacks(go)
            handler.postDelayed(go, 250)
        }
    }

    /** Dunkle Abdeckung, damit Amazons Startbildschirm nicht aufblitzt. Verschwindet spaetestens nach 1,5 s. */
    private fun showCover() {
        handler.removeCallbacks(uncoverLater)
        handler.postDelayed(uncoverLater, 1500)
        if (cover != null) return
        try {
            val v = View(this)
            v.setBackgroundColor(Ui.BG)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            cover = v
        } catch (e: Exception) {
            cover = null
        }
    }

    private fun uncover() {
        handler.removeCallbacks(uncoverLater)
        val v = cover ?: return
        cover = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        uncover()
        super.onDestroy()
    }
}
