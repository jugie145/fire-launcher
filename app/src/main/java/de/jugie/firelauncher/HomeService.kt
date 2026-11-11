package de.jugie.firelauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Oeffnet den eigenen Startbildschirm, sobald Amazons Startbildschirm erscheint. */
class HomeService : AccessibilityService() {

    private val amazon = setOf("com.amazon.tv.launcher", "com.amazon.firelauncher")

    private fun openHome() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (amazon.contains(p) && Prefs.redirect(this)) openHome()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_HOME && Prefs.redirect(this)) {
            if (event.action == KeyEvent.ACTION_UP) openHome()
            return true
        }
        return false
    }

    override fun onInterrupt() {}
}
