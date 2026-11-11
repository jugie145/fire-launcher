package de.jugie.firelauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Home-Tasten-Umleitung einrichten. Amazon blockiert ADB-Verbindungen vom Stick zu sich selbst,
 * deshalb muss die Berechtigung einmalig von einem anderen Geraet aus erteilt werden.
 */
class SetupActivity : Activity() {

    private var k = 1f
    private lateinit var status: TextView
    private lateinit var toggle: TextView

    private fun px(v: Int): Int = (v * k).toInt()

    private fun text(s: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(s: String, onClick: () -> Unit): TextView {
        val t = text(s, 28, Ui.SOFT, false)
        t.setPadding(px(36), px(20), px(36), px(20))
        t.background = Ui.round(Ui.PANEL, 999f)
        t.isFocusable = true
        t.setOnFocusChangeListener { _, has ->
            t.background = Ui.round(if (has) Color.WHITE else Ui.PANEL, 999f)
            t.setTextColor(if (has) Color.BLACK else Ui.SOFT)
        }
        t.setOnClickListener { onClick() }
        return t
    }

    private fun ip(): String {
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: continue
                }
            }
        } catch (e: Exception) {
        }
        return "IP-DES-STICKS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = resources.displayMetrics.widthPixels / 1920f

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Ui.BG)
        col.setPadding(px(160), px(96), px(160), px(72))

        col.addView(text("Home-Taste einrichten", 60, Color.WHITE, true))
        col.addView(
            text(
                "Einmalig von einem Computer oder Android-Handy im selben WLAN diese zwei Befehle ausführen " +
                    "(ADB-Debugging muss am Stick eingeschaltet sein):",
                28, Ui.SOFT, false
            ).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(28) }
        )
        val cmd = text(
            "adb connect " + ip() + ":5555\n" +
                "adb shell pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS",
            28, Color.WHITE, false
        )
        cmd.typeface = Typeface.MONOSPACE
        cmd.setLineSpacing(0f, 1.3f)
        cmd.setPadding(px(32), px(24), px(32), px(24))
        cmd.background = Ui.round(Ui.PANEL, 20f * k)
        col.addView(cmd, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(24) })
        col.addView(
            text(
                "Nach dem ersten Befehl erscheint am Fernseher „Debugging zulassen?“: „Immer zulassen“ anhaken und OK. " +
                    "Danach hier auf „Prüfen“ drücken.",
                28, Ui.SOFT, false
            ).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(24) }
        )

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val check = button("Prüfen") {
            if (Prefs.redirect(this)) Setup.enable(this)
            refresh()
        }
        bar.addView(check)
        toggle = button("") {
            val on = !Prefs.redirect(this)
            Prefs.setRedirect(this, on)
            if (on) Setup.enable(this) else Setup.disable(this)
            refresh()
        }
        bar.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(24) })
        bar.addView(button("Bedienungshilfen öffnen") {
            try {
                HomeService.settingsUntil = SystemClock.uptimeMillis() + 3000
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                status.text = "Die Bedienungshilfen lassen sich auf diesem Stick nicht direkt öffnen."
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(24) })
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(44) })

        status = text("", 28, Ui.MUTED, false)
        col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(40) })

        setContentView(col)
        check.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.redirect(this) && Setup.canWrite(this)) Setup.enable(this)
        refresh()
    }

    private fun refresh() {
        toggle.text = if (Prefs.redirect(this)) "Umleitung: an" else "Umleitung: aus"
        status.text = when {
            Setup.isEnabled(this) && Prefs.redirect(this) -> "Status: aktiv. Drück die Home-Taste zum Testen."
            !Prefs.redirect(this) -> "Status: Umleitung ist ausgeschaltet. Die Home-Taste führt zu Amazon."
            Setup.canWrite(this) -> "Status: Berechtigung vorhanden, Dienst konnte aber nicht aktiviert werden."
            else -> "Status: Berechtigung fehlt noch. Bitte die zwei Befehle oben ausführen."
        }
    }
}
