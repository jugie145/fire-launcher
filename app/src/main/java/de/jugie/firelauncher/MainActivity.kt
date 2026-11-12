package de.jugie.firelauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wave: WaveBackground
    private lateinit var content: LinearLayout
    private lateinit var hero: Hero
    private lateinit var row: LinearLayout
    private lateinit var greet: TextView
    private lateinit var wIcon: WeatherIcon
    private lateinit var wTemp: TextView
    private lateinit var wCond: TextView
    private lateinit var wifiIcon: WifiIcon
    private var favApps: List<AppEntry> = emptyList()
    private var lastFocus = 0
    private var shown: String? = null
    private var spotSet = false
    private var sig = ""
    private var returnToFooter = false
    private var settingsButton: View? = null

    private fun px(v: Int): Int = (v * k).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun lp(w: Int, h: Int, top: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = px(top)
            leftMargin = px(left)
        }

    private fun divider(height: Int, left: Int, right: Int): View {
        val v = View(this)
        v.setBackgroundColor(0x40FFFFFF)
        v.layoutParams = LinearLayout.LayoutParams(Math.max(1, px(2)), px(height)).apply {
            leftMargin = px(left)
            rightMargin = px(right)
        }
        return v
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
        k = resources.displayMetrics.widthPixels / 1920f
        Ui.load(this)
        sig = Prefs.sig(this)
        favApps = emptyList()
        shown = null
        spotSet = false

        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        wave = WaveBackground(this)
        root.addView(wave, FrameLayout.LayoutParams(match, match))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(80), px(48), px(80), px(34))
        content.clipChildren = false
        content.clipToPadding = false
        root.addView(content, FrameLayout.LayoutParams(match, match))

        // Kopfzeile: Uhr | Datum links, Begruessung exakt mittig, Ort | Wetter rechts
        val header = FrameLayout(this)
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val fmt = if (Prefs.h24(this)) "HH:mm" else "h:mm"
        val clock = TextClock(this)
        clock.format24Hour = fmt
        clock.format12Hour = fmt
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 86 * k)
        clock.setTextColor(Color.WHITE)
        clock.typeface = Ui.medium
        clock.includeFontPadding = false
        line.addView(clock)
        line.addView(divider(80, 38, 36))
        val dateCol = LinearLayout(this)
        dateCol.orientation = LinearLayout.VERTICAL
        for (f in arrayOf("EEEE,", "d. MMMM")) {
            val t = TextClock(this)
            t.format24Hour = f
            t.format12Hour = f
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k)
            t.setTextColor(Ui.TEXT_DIM)
            t.includeFontPadding = false
            dateCol.addView(t, lp(wrap, wrap, top = if (f == "EEEE,") 0 else 8))
        }
        line.addView(dateCol)
        line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        line.addView(Ui.text(this, "Grabow", 29f, k, Ui.TEXT_DIM))
        line.addView(divider(72, 36, 34))
        val wCol = LinearLayout(this)
        wCol.orientation = LinearLayout.VERTICAL
        wCol.gravity = Gravity.END
        val wRow = LinearLayout(this)
        wRow.orientation = LinearLayout.HORIZONTAL
        wRow.gravity = Gravity.CENTER_VERTICAL
        wIcon = WeatherIcon(this)
        wRow.addView(wIcon, LinearLayout.LayoutParams(px(48), px(48)))
        wTemp = Ui.text(this, "–°C", 42f, k, Color.WHITE)
        wRow.addView(wTemp, lp(wrap, wrap, left = 12))
        wCond = Ui.text(this, "", 22f, k, Ui.TEXT_DIM)
        wCol.addView(wRow)
        wCol.addView(wCond, lp(wrap, wrap, top = 8))
        line.addView(wCol)
        header.addView(line, FrameLayout.LayoutParams(match, wrap))
        greet = Ui.text(this, "", 37f, k, Color.WHITE)
        header.addView(greet, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        content.addView(header)

        // Grosse Flaeche mit wechselnden Inhalten
        hero = Hero(this, k)
        hero.id = View.generateViewId()
        hero.onOpen = { a, item ->
            if (item == null || !Apps.openLink(this, a, item.link)) Apps.launch(this, a)
        }
        content.addView(hero, lp(match, px(460), top = 44))

        // App-Leiste
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.clipChildren = false
        row.clipToPadding = false
        content.addView(row, lp(match, px(184), top = 62))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Fussleiste
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        settingsButton = footItem(GearIcon(this), "Einstellungen", 252, R.drawable.glass_pill_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        bar.addView(settingsButton)
        bar.addView(footItem(null, "Alle Apps", 162, R.drawable.glass_pill_apps) {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }, LinearLayout.LayoutParams(px(162), px(64)).apply { leftMargin = px(14) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        wifiIcon = WifiIcon(this)
        bar.addView(wifiIcon, LinearLayout.LayoutParams(px(26), px(26)))
        bar.addView(divider(40, 26, 26))
        bar.addView(Ui.text(this, "LIQUID TV · v" + versionName(), 21f, k, Ui.TEXT_DIM))
        content.addView(bar)

        setContentView(root)
    }

    /** Knopf der Fussleiste: bei Fokus blendet das fertige Glasbild ein. */
    private fun footItem(icon: View?, label: String, w: Int, glassRes: Int, onClick: () -> Unit): View {
        val box = FrameLayout(this)
        box.clipChildren = false
        box.layoutParams = LinearLayout.LayoutParams(px(w), px(64))
        val glass = ImageView(this)
        glass.setImageResource(glassRes)
        glass.scaleType = ImageView.ScaleType.FIT_XY
        glass.alpha = 0f
        box.addView(glass, FrameLayout.LayoutParams(px(w + 8), px(72), Gravity.CENTER))
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        val t = Ui.text(this, label, 23f, k, Ui.TEXT_DIM)
        if (icon != null) {
            line.addView(icon, LinearLayout.LayoutParams(px(38), px(38)))
            line.addView(t, lp(wrap, wrap, left = 18))
            box.addView(line, FrameLayout.LayoutParams(wrap, wrap, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = px(20) })
        } else {
            line.addView(t)
            box.addView(line, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        }
        box.isFocusable = true
        box.setOnFocusChangeListener { v, has ->
            glass.animate().alpha(if (has) 1f else 0f).setDuration(Ui.d(180)).start()
            t.setTextColor(if (has) Color.WHITE else Ui.TEXT_DIM)
            val s = if (has) 1.05f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(Ui.d(180)).setInterpolator(Ui.smooth).start()
        }
        box.setOnClickListener { onClick() }
        return box
    }

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    override fun onResume() {
        super.onResume()
        if (Prefs.redirect(this) && Setup.canWrite(this) && !Setup.isEnabled(this)) Setup.enable(this)
        Saver.apply(this)
        if (sig != Prefs.sig(this)) build()
        wave.start()
        hero.start()
        if (returnToFooter) {
            returnToFooter = false
            settingsButton?.post { settingsButton?.requestFocus() }
        }
        if (Ui.anims) {
            content.alpha = 0f
            content.scaleX = 0.98f
            content.scaleY = 0.98f
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Ui.smooth).start()
        }
        updateGreeting()
        updateWifi()
        loadFavs()
        loadWeather()
    }

    override fun onPause() {
        wave.stop()
        hero.stop()
        super.onPause()
    }

    private fun updateGreeting() {
        val name = Prefs.name(this)
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        greet.text = when {
            h >= 23 || h < 5 -> "Noch wach, $name?"
            h in 5..10 -> "Guten Morgen, $name."
            dow == Calendar.FRIDAY && h >= 16 -> "Schönes Wochenende, $name."
            h in 11..13 -> "Mahlzeit, $name."
            h in 14..17 -> "Hallo $name."
            else -> "Guten Abend, $name."
        }
    }

    private fun updateWifi() {
        var on = false
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            on = cm.getNetworkCapabilities(cm.activeNetwork) != null
        } catch (e: Exception) {
        }
        wifiIcon.connected = on
    }

    private fun loadFavs() {
        thread {
            val all = Apps.loadAll(this)
            var favs: List<String>? = Prefs.favs(this)
            if (favs == null || Prefs.favsVersion(this) < Prefs.FAVS_VERSION) {
                val merged = ArrayList(Apps.defaultFavs(all))
                favs?.forEach { if (!merged.contains(it) && merged.size < Prefs.MAX_FAVS) merged.add(it) }
                favs = merged
                Prefs.setFavs(this, merged)
            }
            var cur: List<String> = favs
            // Spotify einmalig als siebte App ergaenzen, sobald es installiert ist
            if (!Prefs.flag(this, "spotify_done")) {
                val sp = all.firstOrNull { it.pkg.lowercase().contains("spotify") || it.label.lowercase().startsWith("spotify") }
                if (sp != null) {
                    if (!cur.contains(sp.pkg) && cur.size < Prefs.MAX_FAVS) {
                        cur = cur + sp.pkg
                        Prefs.setFavs(this, cur)
                    }
                    Prefs.setFlag(this, "spotify_done")
                }
            }
            val byPkg = all.associateBy { it.pkg }
            val list = cur.mapNotNull { byPkg[it] }.take(Prefs.MAX_FAVS)
            handler.post { showFavs(list) }
        }
    }

    /** Grosse Flaeche und Farbbereich des Hintergrunds auf die ausgewaehlte App umstellen. */
    private fun select(app: AppEntry, v: View) {
        val accent = Ui.accentFor(app)
        Ui.lastAccent = accent
        v.post {
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val cx = loc[0] + v.width * v.scaleX / 2f
            val cy = loc[1] + v.height * v.scaleY / 2f
            wave.setSpot(cx, cy, accent, spotSet)
            spotSet = true
        }
        // von der grossen Flaeche mit "Runter" zurueck zu dieser Kachel
        hero.nextFocusDownId = v.id
        if (shown != app.pkg) {
            shown = app.pkg
            hero.show(app)
        }
    }

    private fun showFavs(list: List<AppEntry>) {
        if (row.childCount > 0 && list.isNotEmpty() && list.map { it.pkg } == favApps.map { it.pkg }) return
        favApps = list
        row.removeAllViews()
        if (list.isEmpty()) {
            shown = null
            row.addView(Ui.text(this, "Unter „Alle Apps“ eine App lange gedrückt halten, um sie hier anzupinnen.", 28f, k, Ui.TEXT_DIM))
            return
        }
        val n = list.size
        val w = px(205)
        val h = px(184)
        val avail = resources.displayMetrics.widthPixels - px(160)
        val gap = if (n > 1) Math.max(0, Math.min(px(64), (avail - w * n) / (n - 1))) else 0
        list.forEachIndexed { i, app ->
            val tile = Ui.imageTile(this, app, w, h, k) { v ->
                lastFocus = i
                select(app, v)
            }
            tile.setOnClickListener { v ->
                val opts = try {
                    ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
                } catch (e: Exception) {
                    null
                }
                Apps.launch(this, app, opts)
            }
            tile.id = View.generateViewId()
            tile.nextFocusUpId = hero.id
            tile.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                    event.action == android.view.KeyEvent.ACTION_DOWN) {
                    returnToFooter = true
                    startActivity(Intent(this, CinemaActivity::class.java))
                    true
                } else false
            }
            val cell = FrameLayout(this)
            cell.clipChildren = false
            cell.clipToPadding = false
            cell.addView(tile, FrameLayout.LayoutParams(w, h))
            row.addView(cell, LinearLayout.LayoutParams(w, h).apply { if (i > 0) leftMargin = gap })
            Ui.fadeIn(cell, 60L * i)
        }
        val idx = if (lastFocus < list.size) lastFocus else 0
        (row.getChildAt(idx) as? FrameLayout)?.getChildAt(0)?.requestFocus()
    }

    private fun loadWeather() {
        Weather.load {
            val t = Weather.temp ?: return@load
            wIcon.kind = WeatherIcon.kindFor(Weather.code, Weather.day)
            wTemp.text = "$t°C"
            wCond.text = Weather.text(Weather.code)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Startbildschirm: Zurueck macht nichts
    }
}
