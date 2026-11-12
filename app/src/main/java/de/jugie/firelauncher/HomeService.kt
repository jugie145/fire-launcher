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

        /** App-Start-Animation ueber allem zeigen; false, wenn die Bedienungshilfe nicht laeuft. */
        fun showLaunch(v: LaunchOverlay, pkg: String, extra: Long): Boolean =
            activeService?.startLaunch(v, pkg, extra) ?: false

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
        // Wer waehrend der Start-Animation schon bedient, soll die App sehen
        if (launchView != null && event.action == android.view.KeyEvent.ACTION_DOWN &&
            SystemClock.uptimeMillis() - launchAt > 500) endLaunch(fast = true)
        return false
    }

    // ---------------------------------------------------------------- App-Start-Animation
    private var launchView: LaunchOverlay? = null
    private var launchPkg = ""
    private var launchAt = 0L
    private var launchSeen = 0L
    private var launchChange = 0L
    private var launchExtra = 0L

    private fun startLaunch(v: LaunchOverlay, pkg: String, extra: Long): Boolean {
        removeLaunch()
        return try {
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            launchView = v
            launchPkg = pkg
            launchAt = SystemClock.uptimeMillis()
            launchSeen = 0L
            launchChange = 0L
            launchExtra = extra
            handler.removeCallbacks(launchPoll)
            handler.postDelayed(launchPoll, 100)
            true
        } catch (e: Exception) {
            launchView = null
            false
        }
    }

    /** Bereit = Fenster der App ist da und hat sich eine Weile nicht mehr veraendert (spaetestens nach 6,5 s). */
    private val launchPoll: Runnable = object : Runnable {
        override fun run() {
            if (launchView == null) return
            val now = SystemClock.uptimeMillis()
            val el = now - launchAt
            val ready = launchSeen > 0 && el >= 1100 && now - launchChange >= 600 && now - launchSeen >= launchExtra
            if (ready || el > 6500) {
                endLaunch(fast = false)
                return
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun endLaunch(fast: Boolean) {
        val v = launchView ?: return
        handler.removeCallbacks(launchPoll)
        val gone = { if (launchView === v) removeLaunch() else removeView(v) }
        if (fast) v.finishNow(gone) else v.finish(gone)
        // falls das Zeichnen haengt: spaetestens nach 2 s entfernen
        handler.postDelayed({ if (launchView === v) removeLaunch() }, 2000)
    }

    private fun removeView(v: View) {
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
    }

    private fun removeLaunch() {
        handler.removeCallbacks(launchPoll)
        val v = launchView ?: return
        launchView = null
        removeView(v)
    }

    @Volatile private var fgPkg = ""

    /** In anderen Apps: Schoner nur, wenn nichts abgespielt wird (kein Film, keine Musik). */
    private val idleCheck = object : Runnable {
        override fun run() {
            try {
                val (busy, why) = Playback.videoPlaying(this@HomeService)
                val idleMin = (android.os.SystemClock.uptimeMillis() - Idle.last) / 60000
                Idle.status = "Vordergrund: " + (if (fgPkg.isBlank()) "?" else fgPkg) + " · ohne Eingabe: $idleMin Min. · $why"
                if (fgPkg != packageName && Idle.due(this@HomeService) && !busy) {
                    Idle.start(this@HomeService)
                    Idle.status += " · gestartet"
                }
            } catch (e: Exception) {
                Idle.status = "Fehler: " + e.javaClass.simpleName
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
        if (event != null && launchView != null) {
            val lp = event.packageName?.toString() ?: ""
            if (lp == launchPkg) {
                val now = SystemClock.uptimeMillis()
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && launchSeen == 0L) launchSeen = now
                launchChange = now
            } else if (lp == packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                SystemClock.uptimeMillis() - launchAt > 800) {
                // wieder auf dem Startbildschirm (z. B. Zurueck gedrueckt)
                removeLaunch()
            }
        }
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
        removeLaunch()
        super.onDestroy()
    }
}
