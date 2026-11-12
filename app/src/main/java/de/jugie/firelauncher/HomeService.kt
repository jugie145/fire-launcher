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

        @Volatile private var activeService: HomeService? = null
        @Volatile private var sleepDeadline = 0L

        fun startSleepTimer(minutes: Int): Boolean =
            activeService?.scheduleSleepTimer(minutes) ?: false

        fun cancelSleepTimer() {
            activeService?.cancelSleepTimerInternal()
        }

        fun sleepRemainingSeconds(): Long {
            if (sleepDeadline == 0L) return 0L
            return ((sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L
        }
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
    private val sleepNow = Runnable {
        sleepDeadline = 0L
        // Fire OS may handle this like the remote's sleep action; HDMI-CEC can then switch off the TV.
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 30_000L)
    }

    /** Jede Taste zaehlt als Bedienung; die Taste geht ganz normal weiter. */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        Idle.touch()
        return false
    }

    @Volatile private var fgPkg = ""

    /** In anderen Apps: Schoner nur, wenn nichts abgespielt wird (kein Film, keine Musik). */
    private val idleCheck = object : Runnable {
        override fun run() {
            try {
                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                if (fgPkg != packageName && Idle.due(this@HomeService) && !am.isMusicActive) Idle.start(this@HomeService)
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    private fun scheduleSleepTimer(minutes: Int): Boolean {
        if (minutes <= 0) return false
        handler.removeCallbacks(sleepNow)
        sleepDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(sleepNow, minutes * 60_000L)
        return true
    }

    private fun cancelSleepTimerInternal() {
        handler.removeCallbacks(sleepNow)
        sleepDeadline = 0L
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.packageName?.let { fgPkg = it.toString() }
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
        if (activeService === this) activeService = null
        cancelSleepTimerInternal()
        handler.removeCallbacksAndMessages(null)
        uncover()
        super.onDestroy()
    }
}
