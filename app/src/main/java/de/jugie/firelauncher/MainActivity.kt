package de.jugie.firelauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var row: LinearLayout
    private lateinit var shelfBg: GradientDrawable
    private lateinit var shelfName: TextView
    private lateinit var shelfImage: ImageView
    private lateinit var temp: TextView
    private lateinit var weather: TextView
    private var favApps: List<AppEntry> = emptyList()
    private var lastFocus = 0
    private var lastWeather = 0L

    private fun px(v: Int): Int = (v * k).toInt()

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k)
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = resources.displayMetrics.widthPixels / 1920f

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(px(96), px(72), px(96), px(64))
        root.clipChildren = false
        root.clipToPadding = false

        // Kopfzeile: Uhr + Datum links, Wetter rechts
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val clock = TextClock(this)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 104 * k)
        clock.setTextColor(Color.WHITE)
        clock.typeface = Typeface.DEFAULT_BOLD
        clock.includeFontPadding = false
        val date = TextClock(this)
        date.format24Hour = "EEEE, d. MMMM"
        date.format12Hour = "EEEE, d. MMMM"
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * k)
        date.setTextColor(Ui.MUTED)
        date.includeFontPadding = false
        left.addView(clock)
        left.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        header.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        temp = label("", 64, Color.WHITE, true)
        weather = label("Grabow", 26, Ui.MUTED, false)
        right.addView(temp)
        right.addView(weather, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        header.addView(right)
        root.addView(header)

        // Grosse Flaeche: zeigt die ausgewaehlte App
        val shelf = LinearLayout(this)
        shelf.orientation = LinearLayout.HORIZONTAL
        shelf.gravity = Gravity.CENTER_VERTICAL
        shelfBg = Ui.round(Ui.PANEL, 32f * k)
        shelf.background = shelfBg
        shelf.setPadding(px(72), px(56), px(72), px(56))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.BOTTOM
        val tag = label("AUSGEWÄHLT", 22, 0xC8FFFFFF.toInt(), true)
        tag.letterSpacing = 0.14f
        shelfName = label("", 110, Color.WHITE, true)
        shelfName.maxLines = 1
        val hint = label("OK drücken zum Öffnen", 28, 0xC8FFFFFF.toInt(), false)
        texts.addView(tag)
        texts.addView(shelfName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        texts.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        shelf.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        shelfImage = ImageView(this)
        shelfImage.scaleType = ImageView.ScaleType.CENTER_CROP
        shelfImage.background = Ui.round(0x33000000, 24f * k)
        shelfImage.outlineProvider = ViewOutlineProvider.BACKGROUND
        shelfImage.clipToOutline = true
        shelf.addView(shelfImage, LinearLayout.LayoutParams(px(480), px(270)))
        root.addView(shelf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(400)).apply { topMargin = px(40) })

        // Favoriten
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.clipToPadding = false
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(152)).apply { topMargin = px(52) })

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Untere Leiste
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(pill("Alle Apps") { startActivity(Intent(this, AllAppsActivity::class.java)) })
        bar.addView(pill("Einstellungen") {
            try {
                HomeService.settingsUntil = SystemClock.uptimeMillis() + 3000
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(20) })
        bar.addView(pill("Launcher einrichten") { startActivity(Intent(this, SetupActivity::class.java)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(20) })
        root.addView(bar)

        setContentView(root)
    }

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val t = label(text, 24, Ui.SOFT, false)
        t.setPadding(px(28), px(16), px(28), px(16))
        t.background = Ui.round(Ui.PANEL, 999f)
        t.isFocusable = true
        t.setOnFocusChangeListener { _, has ->
            t.background = Ui.round(if (has) Color.WHITE else Ui.PANEL, 999f)
            t.setTextColor(if (has) Color.BLACK else Ui.SOFT)
        }
        t.setOnClickListener { onClick() }
        return t
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.redirect(this) && Setup.canWrite(this) && !Setup.isEnabled(this)) Setup.enable(this)
        loadFavs()
        if (System.currentTimeMillis() - lastWeather > 30 * 60 * 1000L) loadWeather()
    }

    private fun loadFavs() {
        thread {
            val all = Apps.loadAll(this)
            var favs = Prefs.favs(this)
            if (favs == null) {
                favs = Apps.defaultFavs(all)
                Prefs.setFavs(this, favs)
            }
            val byPkg = all.associateBy { it.pkg }
            val list = favs.mapNotNull { byPkg[it] }.take(Prefs.MAX_FAVS)
            handler.post { showFavs(list) }
        }
    }

    private fun showFavs(list: List<AppEntry>) {
        favApps = list
        row.removeAllViews()
        if (list.isEmpty()) {
            shelfName.text = "Noch keine Favoriten"
            shelfImage.setImageDrawable(null)
            shelfBg.setColor(Ui.PANEL)
            val t = label("Unter „Alle Apps“ eine App lange gedrückt halten, um sie hier anzupinnen.", 28, Ui.MUTED, false)
            row.addView(t)
            return
        }
        val gap = px(40)
        val w = (resources.displayMetrics.widthPixels - px(192) - gap * (Prefs.MAX_FAVS - 1)) / Prefs.MAX_FAVS
        val h = px(152)
        list.forEachIndexed { i, app ->
            val tile = Ui.tile(this, app, h, k) {
                lastFocus = i
                shelfName.text = app.label
                shelfImage.setImageDrawable(app.banner ?: app.icon)
                shelfBg.setColor(Ui.shelfColor(app.banner ?: app.icon))
            }
            tile.setOnClickListener { Apps.launch(this, app) }
            tile.setOnLongClickListener { editFav(app); true }
            tile.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { editFav(app); true } else false
            }
            row.addView(tile, LinearLayout.LayoutParams(w, h).apply { if (i > 0) leftMargin = gap })
        }
        val idx = if (lastFocus < list.size) lastFocus else 0
        row.getChildAt(idx)?.requestFocus()
    }

    private fun editFav(app: AppEntry) {
        val items = arrayOf("Nach links schieben", "Nach rechts schieben", "Aus Favoriten entfernen")
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                val favs = ArrayList(favApps.map { it.pkg })
                val i = favs.indexOf(app.pkg)
                if (i < 0) return@setItems
                when (which) {
                    0 -> if (i > 0) { favs.removeAt(i); favs.add(i - 1, app.pkg); lastFocus = i - 1 }
                    1 -> if (i < favs.size - 1) { favs.removeAt(i); favs.add(i + 1, app.pkg); lastFocus = i + 1 }
                    2 -> { favs.removeAt(i); lastFocus = 0 }
                }
                Prefs.setFavs(this, favs)
                loadFavs()
            }
            .show()
    }

    private fun loadWeather() {
        lastWeather = System.currentTimeMillis()
        thread {
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=53.28&longitude=11.57&current=temperature_2m,weather_code&timezone=auto")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val cur = JSONObject(txt).getJSONObject("current")
                val t = Math.round(cur.getDouble("temperature_2m")).toInt()
                val code = cur.getInt("weather_code")
                handler.post {
                    temp.text = "$t°"
                    weather.text = "Grabow · " + weatherText(code)
                }
            } catch (e: Exception) {
                lastWeather = 0L
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
