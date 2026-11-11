package de.jugie.firelauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aurora: Aurora
    private lateinit var content: LinearLayout
    private lateinit var row: LinearLayout
    private lateinit var greet: TextView
    private lateinit var spotBox: LinearLayout
    private lateinit var spotIcon: ImageView
    private lateinit var spotTitle: TextView
    private lateinit var spotDesc: TextView
    private lateinit var wIcon: WeatherIcon
    private lateinit var wTemp: TextView
    private lateinit var wCond: TextView
    private lateinit var wifiIcon: WifiIcon
    private var favApps: List<AppEntry> = emptyList()
    private var lastFocus = 0
    private var lastWeather = 0L
    private var shown: String? = null
    private var sig = ""

    private fun px(v: Int): Int = (v * k).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun lp(w: Int, h: Int, top: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = px(top)
            leftMargin = px(left)
        }

    private fun divider(height: Int): View {
        val v = View(this)
        v.setBackgroundColor(0x40FFFFFF)
        v.layoutParams = LinearLayout.LayoutParams(Math.max(1, px(2)), px(height))
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
        lastWeather = 0L

        val root = FrameLayout(this)
        aurora = Aurora(this, k)
        aurora.configure(Prefs.bg(this), Prefs.bgAnim(this), Ui.lastAccent)
        root.addView(aurora, FrameLayout.LayoutParams(match, match))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(80), px(48), px(80), px(34))
        content.clipChildren = false
        content.clipToPadding = false
        root.addView(content, FrameLayout.LayoutParams(match, match))

        // Kopfzeile: Uhr | Datum, Begruessung, Standort | Wetter
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val fmt = if (Prefs.h24(this)) "HH:mm" else "h:mm"
        val clock = TextClock(this)
        clock.format24Hour = fmt
        clock.format12Hour = fmt
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 86 * k)
        clock.setTextColor(Color.WHITE)
        clock.typeface = Ui.light
        clock.includeFontPadding = false
        header.addView(clock)
        header.addView(divider(80), (divider(80).layoutParams as LinearLayout.LayoutParams).apply { leftMargin = px(38); rightMargin = px(36) })
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
        header.addView(dateCol)
        greet = Ui.text(this, "", 37f, k, Color.WHITE)
        header.addView(greet, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(110) })

        header.addView(PinIcon(this), LinearLayout.LayoutParams(px(34), px(34)))
        header.addView(Ui.text(this, "Grabow", 29f, k, Ui.TEXT_DIM), lp(wrap, wrap, left = 12))
        header.addView(divider(72), (divider(72).layoutParams as LinearLayout.LayoutParams).apply { leftMargin = px(36); rightMargin = px(34) })
        wIcon = WeatherIcon(this)
        header.addView(wIcon, LinearLayout.LayoutParams(px(66), px(66)))
        val wCol = LinearLayout(this)
        wCol.orientation = LinearLayout.VERTICAL
        wCol.gravity = Gravity.END
        wTemp = Ui.text(this, "–°C", 42f, k, Color.WHITE)
        wCond = Ui.text(this, "", 22f, k, Ui.TEXT_DIM)
        wCol.addView(wTemp)
        wCol.addView(wCond, lp(wrap, wrap, top = 8))
        header.addView(wCol, lp(wrap, wrap, left = 22))
        content.addView(header)

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 3f))

        // Spotlight: zeigt die ausgewaehlte App
        val spot = LinearLayout(this)
        spot.orientation = LinearLayout.HORIZONTAL
        spot.gravity = Gravity.CENTER_VERTICAL
        spot.background = GlassDrawable(42f * k, k)
        spot.setPadding(px(52), 0, px(42), 0)
        spotBox = LinearLayout(this)
        spotBox.orientation = LinearLayout.HORIZONTAL
        spotBox.gravity = Gravity.CENTER_VERTICAL
        spotIcon = ImageView(this)
        spotIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        spotIcon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                val s = Math.min(v.width, v.height)
                val l = (v.width - s) / 2
                val t = (v.height - s) / 2
                o.setRoundRect(l, t, l + s, t + s, s * 0.23f)
            }
        }
        spotIcon.clipToOutline = true
        spotBox.addView(spotIcon, LinearLayout.LayoutParams(px(124), px(92)))
        spotBox.addView(divider(96), (divider(96).layoutParams as LinearLayout.LayoutParams).apply { leftMargin = px(46); rightMargin = px(46) })
        val sCol = LinearLayout(this)
        sCol.orientation = LinearLayout.VERTICAL
        spotTitle = Ui.text(this, "", 40f, k, Color.WHITE, Ui.medium)
        spotDesc = Ui.text(this, "", 27f, k, Ui.TEXT_DIM)
        sCol.addView(spotTitle)
        sCol.addView(spotDesc, lp(wrap, wrap, top = 14))
        spotBox.addView(sCol)
        spot.addView(spotBox, LinearLayout.LayoutParams(0, wrap, 1f))
        spot.addView(PlayIcon(this), LinearLayout.LayoutParams(px(82), px(82)))
        content.addView(spot, LinearLayout.LayoutParams(match, px(152)))

        // App-Leiste
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.clipChildren = false
        row.clipToPadding = false
        content.addView(row, lp(match, px(206), top = 62))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Fussleiste
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        bar.addView(footItem(GearIcon(this), "Einstellungen") { startActivity(Intent(this, SettingsActivity::class.java)) })
        bar.addView(footItem(null, "Alle Apps") { startActivity(Intent(this, AllAppsActivity::class.java)) }, lp(wrap, wrap, left = 14))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        wifiIcon = WifiIcon(this)
        bar.addView(wifiIcon, LinearLayout.LayoutParams(px(26), px(26)))
        bar.addView(divider(40), (divider(40).layoutParams as LinearLayout.LayoutParams).apply { leftMargin = px(26); rightMargin = px(26) })
        bar.addView(Ui.text(this, "LIQUID TV · v" + versionName(), 21f, k, Ui.TEXT_DIM))
        content.addView(bar)

        setContentView(root)
    }

    private fun footItem(icon: View?, label: String, onClick: () -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(px(20), px(12), px(26), px(12))
        val g = GlassDrawable(999f, k)
        val t = Ui.text(this, label, 23f, k, Ui.TEXT_DIM)
        if (icon != null) {
            box.addView(icon, LinearLayout.LayoutParams(px(38), px(38)))
            box.addView(t, lp(wrap, wrap, left = 18))
        } else {
            box.addView(t, lp(wrap, wrap, left = 6))
        }
        box.isFocusable = true
        box.setOnFocusChangeListener { v, has ->
            g.boost = if (has) 1f else 0f
            box.background = if (has) g else null
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
        if (sig != Prefs.sig(this)) build()
        aurora.start()
        if (Ui.anims) {
            content.alpha = 0f
            content.scaleX = 0.98f
            content.scaleY = 0.98f
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Ui.smooth).start()
        }
        updateGreeting()
        updateWifi()
        loadFavs()
        if (System.currentTimeMillis() - lastWeather > 30 * 60 * 1000L) loadWeather()
    }

    override fun onPause() {
        aurora.stop()
        super.onPause()
    }

    private fun updateGreeting() {
        val name = Prefs.name(this)
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        greet.text = when {
            h >= 23 || h < 5 -> "Noch wach, $name? 🌙"
            h in 5..10 -> "Guten Morgen, $name. ☀️"
            dow == Calendar.FRIDAY && h >= 16 -> "Schönes Wochenende, $name. 👋"
            h in 11..13 -> "Mahlzeit, $name. 👋"
            h in 14..17 -> "Hallo $name. 👋"
            else -> "Guten Abend, $name. 👋"
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

    /** Spotlight und Hintergrund auf die ausgewaehlte App umstellen. */
    private fun select(app: AppEntry) {
        if (shown == app.pkg) return
        shown = app.pkg
        val accent = Ui.accentFor(app)
        Ui.lastAccent = accent
        aurora.setAccent(accent)
        val logo = Ui.logoRes(app)
        if (logo != 0) {
            spotIcon.clipToOutline = false
            spotIcon.setImageResource(logo)
        } else {
            spotIcon.clipToOutline = true
            spotIcon.setImageDrawable(app.icon ?: app.banner)
        }
        spotTitle.text = app.label
        spotDesc.text = Ui.descFor(app)
        spotBox.animate().cancel()
        if (Ui.anims) {
            spotBox.alpha = 0f
            spotBox.translationX = 26f * k
            spotBox.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(Ui.smooth).start()
        } else {
            spotBox.alpha = 1f
            spotBox.translationX = 0f
        }
    }

    private fun showFavs(list: List<AppEntry>) {
        if (row.childCount > 0 && list.isNotEmpty() && list.map { it.pkg } == favApps.map { it.pkg }) return
        favApps = list
        row.removeAllViews()
        if (list.isEmpty()) {
            shown = null
            spotTitle.text = "Noch keine Favoriten"
            spotDesc.text = "Unter „Alle Apps“ eine App lange gedrückt halten, um sie anzupinnen."
            spotIcon.setImageDrawable(null)
            return
        }
        // Kacheln fuellen die Breite gleichmaessig; Groesse richtet sich nach der Anzahl
        val n = list.size
        val gap = px(28)
        val avail = resources.displayMetrics.widthPixels - px(160)
        val w = Math.min(px(270), (avail - gap * (n - 1)) / n)
        val h = Math.min(px(206), (w * 0.9f).toInt())
        list.forEachIndexed { i, app ->
            val tile = Ui.glassTile(this, app, w, h, k, true) {
                lastFocus = i
                select(app)
            }
            tile.setOnClickListener { v ->
                val opts = try {
                    ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
                } catch (e: Exception) {
                    null
                }
                Apps.launch(this, app, opts)
            }
            row.addView(tile, LinearLayout.LayoutParams(w, h).apply { if (i > 0) leftMargin = gap })
            Ui.fadeIn(tile, 60L * i)
        }
        val idx = if (lastFocus < list.size) lastFocus else 0
        row.getChildAt(idx)?.requestFocus()
    }

    private fun loadWeather() {
        lastWeather = System.currentTimeMillis()
        thread {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=53.28&longitude=11.57" +
                        "&current=temperature_2m,weather_code,is_day&timezone=auto"
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val cur = JSONObject(txt).getJSONObject("current")
                val t = Math.round(cur.getDouble("temperature_2m")).toInt()
                val code = cur.getInt("weather_code")
                val day = cur.optInt("is_day", 1) == 1
                handler.post {
                    wIcon.kind = WeatherIcon.kindFor(code, day)
                    wTemp.text = "$t°C"
                    wCond.text = weatherText(code)
                }
            } catch (e: Exception) {
                lastWeather = 0L
            }
        }
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Leicht bewölkt"
        3 -> "Bewölkt"
        45, 48 -> "Nebel"
        51, 53, 55, 56, 57 -> "Nieselregen"
        61, 63, 65, 66, 67 -> "Regen"
        71, 73, 75, 77 -> "Schnee"
        80, 81, 82 -> "Schauer"
        85, 86 -> "Schneeschauer"
        95, 96, 99 -> "Gewitter"
        else -> ""
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Startbildschirm: Zurueck macht nichts
    }
}
