package de.jugie.firelauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import kotlin.concurrent.thread

/** Richtet die Home-Tasten-Umleitung direkt am Stick ein (ohne PC). */
class SetupActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: TextView
    private var busy = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = resources.displayMetrics.widthPixels / 1920f

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Ui.BG)
        col.setPadding(px(160), px(120), px(160), px(96))

        col.addView(text("Home-Taste einrichten", 64, Color.WHITE, true))
        col.addView(
            text(
                "Damit die Home-Taste hier landet statt bei Amazon:\n\n" +
                    "1.  In den Fire-TV-Einstellungen muss „ADB-Debugging“ eingeschaltet sein.\n" +
                    "2.  Unten auf „Jetzt einrichten“ drücken.\n" +
                    "3.  Erscheint „Debugging zulassen?“: „Immer zulassen“ anhaken und OK drücken.",
                30, Ui.SOFT, false
            ).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(32) }
        )

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val run = button("Jetzt einrichten") { runSetup() }
        bar.addView(run)
        toggle = button("") {
            val on = !Prefs.redirect(this)
            Prefs.setRedirect(this, on)
            if (on) Setup.enable(this)
            refresh(null)
        }
        bar.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(24) })
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(56) })

        status = text("", 28, Ui.MUTED, false)
        col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(48) })

        setContentView(col)
        run.requestFocus()
        refresh(null)
    }

    private fun refresh(extra: String?) {
        toggle.text = if (Prefs.redirect(this)) "Umleitung: an" else "Umleitung: aus"
        val base = when {
            Setup.isEnabled(this) && Prefs.redirect(this) -> "Status: aktiv. Die Home-Taste führt zu diesem Launcher."
            Setup.isEnabled(this) -> "Status: eingerichtet, aber Umleitung ist ausgeschaltet."
            Setup.canWrite(this) -> "Status: Berechtigung vorhanden, Dienst noch nicht aktiv."
            else -> "Status: noch nicht eingerichtet."
        }
        status.text = if (extra == null) base else extra + "\n\n" + base
    }

    private fun runSetup() {
        if (busy) return
        busy = true
        status.text = "Verbinde … Falls ein Fenster „Debugging zulassen?“ erscheint: „Immer zulassen“ anhaken und OK."
        thread {
            var msg: String
            try {
                val priv = File(filesDir, "adbkey")
                val pub = File(filesDir, "adbkey.pub")
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
                val kp = AdbKeyPair.read(priv, pub)
                Dadb.create("127.0.0.1", 5555, kp).use { d ->
                    val r = d.shell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
                    msg = if (r.exitCode == 0) "" else "Antwort vom Stick: " + r.allOutput.trim()
                }
                Prefs.setRedirect(this, true)
                val ok = Setup.enable(this)
                msg = if (ok) "Fertig. Drück jetzt die Home-Taste zum Testen." else ("Berechtigung konnte nicht gesetzt werden. " + msg)
            } catch (e: Exception) {
                msg = "Hat nicht geklappt: " + (e.message ?: e.javaClass.simpleName) +
                    "\nIst ADB-Debugging in den Entwickleroptionen eingeschaltet?"
            }
            handler.post {
                busy = false
                refresh(msg)
            }
        }
    }
}
