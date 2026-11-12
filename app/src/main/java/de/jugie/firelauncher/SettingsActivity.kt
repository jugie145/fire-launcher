package de.jugie.firelauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.concurrent.thread

/** Eigene Einstellungen im Glas-Design, komplett mit der Fernbedienung bedienbar. */
class SettingsActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wave: WaveBackground
    private lateinit var orderRow: LinearLayout
    private lateinit var orderHint: TextView
    private val glasses = ArrayList<GlassDrawable>()
    private val refreshers = ArrayList<() -> Unit>()
    private var favs = ArrayList<AppEntry>()
    private var grabbed = -1
    private var bgSig = ""
    private var glassSig = -1
    private val sleepDurations = listOf(15, 30, 45, 60, 90, 120)
    private var sleepDurationIndex = 1
    private val sleepRefresh = object : Runnable {
        override fun run() {
            for (r in refreshers) r()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun px(v: Int): Int = (v * k).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun onOff(b: Boolean): String = if (b) "An" else "Aus"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = resources.displayMetrics.widthPixels / 1920f
        Ui.load(this)
        LocalServer.start(this)
        bgSig = "" + Prefs.bgAnim(this) + Prefs.anims(this)
        glassSig = Prefs.glass(this)

        wave = WaveBackground(this)
        val scroll = ScrollView(this)
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(px(420), px(72), px(420), px(96))
        col.clipChildren = false
        col.clipToPadding = false

        col.addView(Ui.text(this, "Einstellungen", 60f, k, Color.WHITE, Ui.light))

        section(col, "Darstellung")
        val first = row(col, "Hintergrundanimation", { onOff(Prefs.bgAnim(this)) }) { Prefs.setBgAnim(this, !Prefs.bgAnim(this)) }
        row(col, "Glaseffekt", { arrayOf("Dezent", "Normal", "Stark")[Prefs.glass(this) % 3] }) {
            Prefs.setGlass(this, (Prefs.glass(this) + 1) % 3)
        }
        row(col, "Animationen", { onOff(Prefs.anims(this)) }) { Prefs.setAnims(this, !Prefs.anims(this)) }
        row(col, "Uhrzeitformat", { if (Prefs.h24(this)) "24 Stunden" else "12 Stunden" }) { Prefs.setH24(this, !Prefs.h24(this)) }
        row(col, "Design", { if (Prefs.design(this) == 1) "GTA VI" else "Liquid Glass" }) {
            Prefs.setDesign(this, if (Prefs.design(this) == 1) 0 else 1)
        }
        row(col, "Farben im Hintergrund", { if (Prefs.contentColors(this)) "Aus dem Inhalt" else "App-Farbe" }) {
            Prefs.setContentColors(this, !Prefs.contentColors(this))
        }

        section(col, "Spotify")
        row(col, "Spotify", { if (Spotify.connected(this)) "Verbunden" else "Nicht verbunden" }) {
            if (Spotify.connected(this)) Spotify.logout(this)
        }
        info(col, "Zum Verbinden am Handy (im selben WLAN) öffnen:\nhttp://" + LocalServer.address() + "/login\n" +
            "Dort bei Spotify anmelden – danach ist alles fertig. Spotify Premium wird für die Steuerung benötigt.", false)

        section(col, "GTA-Hintergründe")
        row(col, "Bilder auf dem Fernseher", { "" + Theme.bgFiles(this).size }) { }
        info(col, "Zum Hochladen am Handy (im selben WLAN) öffnen:\nhttp://" + LocalServer.address() + "/bilder\n" +
            "Bilder auswählen – sie wechseln im GTA-Design zufällig. Die Bilder bleiben nur auf dem Fernseher.", false)

        section(col, "App-Reihenfolge")
        orderRow = LinearLayout(this)
        orderRow.orientation = LinearLayout.HORIZONTAL
        orderRow.gravity = Gravity.CENTER_VERTICAL
        orderRow.clipChildren = false
        orderRow.clipToPadding = false
        col.addView(orderRow, LinearLayout.LayoutParams(match, px(150)).apply { topMargin = px(22) })
        orderHint = Ui.text(this, "", 22f, k, Ui.TEXT_DIM)
        col.addView(orderHint, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(16) })

        section(col, "Bildschirmschoner")
        row(col, "Bildschirmschoner", {
            if (!Setup.canWrite(this)) "Berechtigung fehlt" else Saver.NAMES[Prefs.saver(this) % 3]
        }) {
            Prefs.setSaver(this, (Prefs.saver(this) + 1) % 3)
            Saver.apply(this)
        }
        row(col, "Startet nach", { "" + Prefs.saverMinutes(this) + " Minuten" }) {
            val i = Saver.MINUTES.indexOf(Prefs.saverMinutes(this))
            Prefs.setSaverMinutes(this, Saver.MINUTES[(i + 1) % Saver.MINUTES.size])
            Saver.apply(this)
        }
        row(col, "Bildschirmschoner ansehen", { "Starten" }) {
            startActivity(Intent(this, ScreensaverActivity::class.java))
        }

        section(col, "System")
        row(col, "Home-Taste umleiten", {
            when {
                !Prefs.redirect(this) -> "Aus"
                Setup.isEnabled(this) -> "An"
                else -> "Berechtigung fehlt"
            }
        }) {
            val on = !Prefs.redirect(this)
            Prefs.setRedirect(this, on)
            if (on) Setup.enable(this) else Setup.disable(this)
        }
        if (!Setup.canWrite(this)) {
            info(col, "Einmalig von einem anderen Gerät im selben WLAN ausführen:\n" +
                "adb connect " + ip() + ":5555\n" +
                "adb shell pm grant " + packageName + " android.permission.WRITE_SECURE_SETTINGS", true)
        }
        row(col, "Fire-TV-Einstellungen", { "Öffnen" }) {
            try {
                HomeService.settingsUntil = SystemClock.uptimeMillis() + 3000
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }

        section(col, "Sleep-Timer")
        row(col, "Dauer auswählen", { "${sleepDurations[sleepDurationIndex]} Minuten" }) {
            sleepDurationIndex = (sleepDurationIndex + 1) % sleepDurations.size
        }
        row(col, "Timer starten", {
            val seconds = HomeService.sleepRemainingSeconds()
            if (seconds > 0L) "Läuft" else "Starten"
        }) {
            HomeService.startSleepTimer(sleepDurations[sleepDurationIndex])
        }
        row(col, "Verbleibende Zeit", {
            val seconds = HomeService.sleepRemainingSeconds()
            if (seconds <= 0L) "Nicht aktiv"
            else String.format(java.util.Locale.GERMANY, "%02d:%02d", seconds / 60L, seconds % 60L)
        }) { }
        row(col, "Timer abbrechen", { "Abbrechen" }) {
            HomeService.cancelSleepTimer()
        }

        section(col, "Systeminformationen")
        info(col, sysInfo(), false)
        info(col, "Datenquellen: Filme und Serien von TMDB, Anbieter-Daten von JustWatch. Diese App nutzt die " +
            "TMDB-Schnittstelle, ist aber nicht von TMDB empfohlen oder zertifiziert. Musik-Charts: Apple Music. " +
            "Fußball: OpenLigaDB. Wetter: Open-Meteo.", false)

        scroll.addView(col)
        val frame = FrameLayout(this)
        frame.addView(wave, FrameLayout.LayoutParams(match, match))
        frame.addView(scroll, FrameLayout.LayoutParams(match, match))
        setContentView(frame)
        first.requestFocus()

        thread {
            val all = Apps.loadAll(this).associateBy { it.pkg }
            val list = (Prefs.favs(this) ?: emptyList()).mapNotNull { all[it] }.take(Prefs.MAX_FAVS)
            handler.post {
                favs = ArrayList(list)
                buildOrder(-1)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wave.start()
        // kleiner Webserver fuer Spotify-Anmeldung und Bilder vom Handy, nur solange diese Seite offen ist
        LocalServer.onChange = { for (r in refreshers) r() }
        LocalServer.start(this)
        if (Prefs.redirect(this) && Setup.canWrite(this)) Setup.enable(this)
        handler.removeCallbacks(sleepRefresh)
        handler.post(sleepRefresh)
    }

    override fun onPause() {
        LocalServer.onChange = null
        LocalServer.stopLater()
        handler.removeCallbacks(sleepRefresh)
        wave.stop()
        super.onPause()
    }

    private fun section(col: LinearLayout, title: String) {
        val t = Ui.text(this, title.uppercase(), 20f, k, Ui.TEXT_FAINT, Ui.medium)
        t.letterSpacing = 0.14f
        col.addView(t, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(52) })
    }

    private fun row(col: LinearLayout, label: String, value: () -> String, onClick: () -> Unit): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val g = GlassDrawable(28f * k, k)
        glasses.add(g)
        r.background = g
        r.setPadding(px(36), 0, px(36), 0)
        r.isFocusable = true
        val l = Ui.text(this, label, 28f, k, Color.WHITE)
        val v = Ui.text(this, value(), 28f, k, Ui.TEXT_DIM)
        r.addView(l, LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(v)
        refreshers.add { v.text = value() }
        r.setOnFocusChangeListener { view, has ->
            g.boost = if (has) 1f else 0f
            v.setTextColor(if (has) Color.WHITE else Ui.TEXT_DIM)
            val s = if (has) 1.025f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(Ui.d(180)).setInterpolator(Ui.smooth).start()
        }
        r.setOnClickListener {
            onClick()
            applyChanges()
        }
        col.addView(r, LinearLayout.LayoutParams(match, px(88)).apply { topMargin = px(16) })
        return r
    }

    private fun info(col: LinearLayout, text: String, mono: Boolean) {
        val t = Ui.text(this, text, 23f, k, Ui.TEXT_DIM)
        if (mono) t.typeface = Typeface.MONOSPACE
        t.setLineSpacing(0f, 1.35f)
        val g = GlassDrawable(28f * k, k)
        glasses.add(g)
        t.background = g
        t.setPadding(px(36), px(26), px(36), px(26))
        t.isFocusable = true
        t.setOnFocusChangeListener { _, has -> g.boost = if (has) 0.6f else 0f }
        col.addView(t, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(16) })
    }

    /** Nach einer Aenderung alles Sichtbare sofort anpassen. */
    private fun applyChanges() {
        Ui.load(this)
        for (r in refreshers) r()
        val nb = "" + Prefs.bgAnim(this) + Prefs.anims(this)
        if (nb != bgSig) {
            bgSig = nb
            wave.start()
        }
        if (Prefs.glass(this) != glassSig) {
            glassSig = Prefs.glass(this)
            for (g in glasses) g.rebuild()
            val f = orderRow.focusedChild
            buildOrder(if (f != null) orderRow.indexOfChild(f) else -1)
        }
    }

    /** Favoriten als kleine Kacheln: OK greift eine App, links/rechts verschiebt, OK legt ab. */
    private fun buildOrder(focus: Int) {
        orderRow.removeAllViews()
        if (favs.isEmpty()) {
            orderHint.text = "Noch keine Favoriten. Unter „Alle Apps“ eine App lange gedrückt halten."
            return
        }
        orderHint.text = if (grabbed >= 0) "Mit ◀ ▶ verschieben, OK zum Ablegen."
        else "OK greift eine App, dann mit ◀ ▶ verschieben."
        val size = px(112)
        favs.forEachIndexed { i, app ->
            val tile = Ui.glassTile(this, app, size, size, k, false)
            if (i == grabbed) tile.alpha = 0.72f
            tile.setOnClickListener {
                grabbed = if (grabbed == i) -1 else i
                buildOrder(i)
            }
            tile.setOnKeyListener { _, code, ev ->
                val side = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                if (grabbed == i && side) {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        val j = if (code == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                        if (j >= 0 && j < favs.size) {
                            val a = favs[i]
                            favs[i] = favs[j]
                            favs[j] = a
                            Prefs.setFavs(this, favs.map { it.pkg })
                            grabbed = j
                            buildOrder(j)
                        }
                    }
                    true
                } else {
                    if (grabbed == i && ev.action == KeyEvent.ACTION_DOWN &&
                        (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK)
                    ) {
                        grabbed = -1
                        orderHint.text = "OK greift eine App, dann mit ◀ ▶ verschieben."
                        tile.alpha = 1f
                    }
                    false
                }
            }
            orderRow.addView(tile, LinearLayout.LayoutParams(size, size).apply { if (i > 0) leftMargin = px(46) })
        }
        if (focus >= 0 && focus < orderRow.childCount) orderRow.getChildAt(focus).requestFocus()
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

    private fun sysInfo(): String {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        var mem = ""
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mem = "" + (mi.availMem / 1048576L) + " MB frei von " + (mi.totalMem / 1048576L) + " MB"
        } catch (e: Exception) {
        }
        val dm = resources.displayMetrics
        return "Liquid TV " + ver + "\n" +
            "Gerät: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Oberfläche: " + dm.widthPixels + " × " + dm.heightPixels + "\n" +
            "Arbeitsspeicher: " + mem + "\n" +
            "IP-Adresse: " + ip()
    }
}
