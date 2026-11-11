package de.jugie.firelauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private lateinit var appName: TextView
    private lateinit var greet: TextView
    private lateinit var greetSub: TextView
    private lateinit var wIcon: WeatherIcon
    private lateinit var wTemp: TextView
    private lateinit var wCond: TextView
    private lateinit var wExtra: TextView
    private lateinit var wifiIcon: WifiIcon
    private lateinit var wifiText: TextView
    private var favApps: List<AppEntry> = emptyList()
    private var lastFocus = 0
    private var lastWeather = 0L
    private var weatherCode = -1
    private var shown: String? = null
    private var sig = ""

    private fun px(v: Int): Int = (v * k).toInt()

    private fun lp(w: Int, h: Int, top: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = px(top)
            leftMargin = px(left)
        }

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

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
        aurora.configure(Prefs.bg(this), Prefs.bgAnim(this))
        root.addView(aurora, FrameLayout.LayoutParams(match, match))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(120), px(64), px(120), px(44))
        content.clipChildren = false
        content.clipToPadding = false
        root.addView(content, FrameLayout.LayoutParams(match, match))

        // Oben links: Uhrzeit und Datum. Oben rechts: Wetter.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.clipChildren = false
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val fmt = if (Prefs.h24(this)) "HH:mm" else "h:mm a"
        val clock = TextClock(this)
        clock.format24Hour = fmt
        clock.format12Hour = fmt
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 118 * k)
        clock.setTextColor(Color.WHITE)
        clock.typeface = Ui.light
        clock.includeFontPadding = false
        clock.letterSpacing = -0.02f
        val date = TextClock(this)
        date.format24Hour = "EEEE, d. MMMM yyyy"
        date.format12Hour = "EEEE, d. MMMM yyyy"
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k)
        date.setTextColor(Ui.TEXT_DIM)
        date.includeFontPadding = false
        left.addView(clock)
        left.addView(date, lp(wrap, wrap, top = 12))
        header.addView(left, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(weatherWidget())
        content.addView(header)

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.85f))

        // Begruessung
        greet = Ui.text(this, "", 66f, k, Color.WHITE, Ui.light)
        greet.gravity = Gravity.CENTER_HORIZONTAL
        greetSub = Ui.text(this, "", 28f, k, Ui.TEXT_DIM)
        greetSub.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(greet, lp(match, wrap))
        content.addView(greetSub, lp(match, wrap, top = 16))

        // Sechs Glaskacheln in einer Reihe
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.clipChildren = false
        row.clipToPadding = false
        content.addView(row, lp(match, px(216), top = 84))

        appName = Ui.text(this, "", 38f, k, Color.WHITE, Ui.medium)
        appName.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(appName, lp(match, wrap, top = 52))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Dezente Systemleiste
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false
        val status = LinearLayout(this)
        status.orientation = LinearLayout.HORIZONTAL
        status.gravity = Gravity.CENTER_VERTICAL
        wifiIcon = WifiIcon(this)
        wifiText = Ui.text(this, "", 20f, k, Ui.TEXT_FAINT)
        status.addView(wifiIcon, LinearLayout.LayoutParams(px(26), px(26)))
        status.addView(wifiText, lp(wrap, wrap, left = 12))
        bar.addView(status, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(Ui.pill(this, "Alle Apps", k) { startActivity(Intent(this, AllAppsActivity::class.java)) })
        bar.addView(Ui.pill(this, "Einstellungen", k) { startActivity(Intent(this, SettingsActivity::class.java)) }, lp(wrap, wrap, left = 18))
        val ver = Ui.text(this, "Fire Launcher " + versionName(), 20f, k, Ui.TEXT_FAINT)
        ver.gravity = Gravity.END
        bar.addView(ver, LinearLayout.LayoutParams(0, wrap, 1f))
        content.addView(bar)

        setContentView(root)
    }

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun weatherWidget(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = GlassDrawable(34f * k, k)
        box.setPadding(px(28), px(22), px(34), px(22))
        wIcon = WeatherIcon(this)
        box.addView(wIcon, LinearLayout.LayoutParams(px(68), px(68)))
        wTemp = Ui.text(this, "–°", 62f, k, Color.WHITE, Ui.light)
        box.addView(wTemp, lp(wrap, wrap, left = 18))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(this, "Grabow", 24f, k, Color.WHITE, Ui.medium))
        wCond = Ui.text(this, "Wetter wird geladen", 21f, k, Ui.TEXT_DIM)
        wExtra = Ui.text(this, "", 19f, k, Ui.TEXT_FAINT)
        col.addView(wCond, lp(wrap, wrap, top = 6))
        col.addView(wExtra, lp(wrap, wrap, top = 5))
        box.addView(col, lp(wrap, wrap, left = 24))
        return box
    }

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
        val wet = weatherCode in intArrayOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
        val snow = weatherCode in intArrayOf(71, 73, 75, 77, 85, 86)
        greet.text = when (h) {
            in 5..10 -> "Guten Morgen, $name"
            in 11..13 -> "Mahlzeit, $name"
            in 14..17 -> "Hallo $name"
            in 18..22 -> "Guten Abend, $name"
            else -> "Noch wach, $name?"
        }
        greetSub.text = when {
            h >= 23 || h < 5 -> "Eine Folge geht noch."
            wet && h >= 16 -> "Draußen ist es nass. Perfektes Wetter für die Couch."
            snow -> "Draußen schneit es. Mach es dir gemütlich."
            dow == Calendar.FRIDAY && h >= 16 -> "Feierabend. Das Wochenende gehört dir."
            dow == Calendar.SATURDAY && h < 12 -> "Entspannter Start ins Wochenende."
            dow == Calendar.SUNDAY && h >= 18 -> "Lass den Sonntag gemütlich ausklingen."
            dow == Calendar.SUNDAY -> "Entspannten Sonntag."
            h in 5..10 -> "Hab einen guten Start in den Tag."
            h in 18..22 -> "Zeit zum Abschalten. Was schauen wir heute?"
            else -> "Was schauen wir heute?"
        }
    }

    private fun updateWifi() {
        var txt = "Offline"
        var on = false
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                on = true
                txt = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN verbunden"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "LAN verbunden"
                    else -> "Verbunden"
                }
            }
        } catch (e: Exception) {
        }
        wifiIcon.connected = on
        wifiText.text = txt
    }

    private fun loadFavs() {
        thread {
            val all = Apps.loadAll(this)
            var favs = Prefs.favs(this)
            if (favs == null || Prefs.favsVersion(this) < Prefs.FAVS_VERSION) {
                val merged = ArrayList(Apps.defaultFavs(all))
                favs?.forEach { if (!merged.contains(it) && merged.size < Prefs.MAX_FAVS) merged.add(it) }
                favs = merged
                Prefs.setFavs(this, merged)
            }
            val byPkg = all.associateBy { it.pkg }
            val list = favs.mapNotNull { byPkg[it] }.take(Prefs.MAX_FAVS)
            handler.post { showFavs(list) }
        }
    }

    private fun showName(app: AppEntry) {
        if (shown == app.pkg) return
        shown = app.pkg
        appName.animate().cancel()
        appName.text = app.label
        if (Ui.anims) {
            appName.alpha = 0f
            appName.translationY = 10f * k
            appName.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(Ui.smooth).start()
        } else {
            appName.alpha = 1f
            appName.translationY = 0f
        }
    }

    private fun showFavs(list: List<AppEntry>) {
        if (row.childCount > 0 && list.isNotEmpty() && list.map { it.pkg } == favApps.map { it.pkg }) return
        favApps = list
        row.removeAllViews()
        if (list.isEmpty()) {
            shown = null
            appName.text = ""
            row.addView(Ui.text(this, "Unter „Alle Apps“ eine App lange gedrückt halten, um sie hier anzupinnen.", 28f, k, Ui.TEXT_DIM))
            return
        }
        // gleichmaessig ueber die Breite verteilt
        val size = px(216)
        val avail = resources.displayMetrics.widthPixels - px(240)
        val gap = if (list.size > 1) Math.min(px(120), (avail - size * list.size) / (list.size - 1)) else 0
        list.forEachIndexed { i, app ->
            val tile = Ui.glassTile(this, app, size, k) {
                lastFocus = i
                showName(app)
            }
            tile.setOnClickListener { v ->
                val opts = try {
                    ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
                } catch (e: Exception) {
                    null
                }
                Apps.launch(this, app, opts)
            }
            row.addView(tile, LinearLayout.LayoutParams(size, size).apply { if (i > 0) leftMargin = gap })
            Ui.fadeIn(tile, 70L * i)
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
                        "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                        "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(txt)
                val cur = json.getJSONObject("current")
                val t = Math.round(cur.getDouble("temperature_2m")).toInt()
                val feels = Math.round(cur.getDouble("apparent_temperature")).toInt()
                val code = cur.getInt("weather_code")
                val day = cur.optInt("is_day", 1) == 1
                val daily = json.getJSONObject("daily")
                val hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
                val lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
                handler.post {
                    weatherCode = code
                    wIcon.kind = WeatherIcon.kindFor(code, day)
                    wTemp.text = "$t°"
                    wCond.text = weatherText(code)
                    wExtra.text = "Gefühlt $feels°  ·  H $hi°  T $lo°"
                    updateGreeting()
                }
            } catch (e: Exception) {
                lastWeather = 0L
                handler.post { wCond.text = "Wetter nicht verfügbar" }
            }
        }
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Teils bewölkt"
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
