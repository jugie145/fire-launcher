package de.jugie.firelauncher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
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
    private val ease = DecelerateInterpolator(1.6f)
    private lateinit var aurora: Aurora
    private lateinit var content: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var shelfTexts: LinearLayout
    private lateinit var shelfName: TextView
    private lateinit var shelfImage: ImageView
    private lateinit var temp: TextView
    private lateinit var weather: TextView
    private var favApps: List<AppEntry> = emptyList()
    private var lastFocus = 0
    private var lastWeather = 0L
    private var shown: String? = null

    private fun px(v: Int): Int = (v * k).toInt()

    private val light: Typeface by lazy { Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val medium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private fun label(text: String, size: Int, color: Int, face: Typeface?): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k)
            setTextColor(color)
            includeFontPadding = false
            if (face != null) typeface = face
        }

    private fun wrap(top: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(top)
            leftMargin = px(left)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = resources.displayMetrics.widthPixels / 1920f

        val root = FrameLayout(this)
        aurora = Aurora(this, k)
        root.addView(aurora, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(px(96), px(60), px(96), px(52))
        content.clipChildren = false
        content.clipToPadding = false
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Kopfzeile: Uhr + Datum links, Wetter rechts
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val clock = TextClock(this)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 112 * k)
        clock.setTextColor(Color.WHITE)
        clock.typeface = light
        clock.includeFontPadding = false
        val date = TextClock(this)
        date.format24Hour = "EEEE, d. MMMM"
        date.format12Hour = "EEEE, d. MMMM"
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * k)
        date.setTextColor(0xD9FFFFFF.toInt())
        date.includeFontPadding = false
        left.addView(clock)
        left.addView(date, wrap(top = 10))
        header.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        temp = label("", 72, Color.WHITE, light)
        weather = label("Grabow", 26, 0xD9FFFFFF.toInt(), null)
        right.addView(temp)
        right.addView(weather, wrap(top = 8))
        header.addView(right)
        content.addView(header)

        // Grosse Glasflaeche: zeigt die ausgewaehlte App
        val shelf = LinearLayout(this)
        shelf.orientation = LinearLayout.HORIZONTAL
        shelf.gravity = Gravity.CENTER_VERTICAL
        shelf.background = Ui.glass(40f * k, k)
        shelf.setPadding(px(72), px(48), px(64), px(48))
        shelfTexts = LinearLayout(this)
        shelfTexts.orientation = LinearLayout.VERTICAL
        shelfTexts.gravity = Gravity.CENTER_VERTICAL
        shelfName = label("", 104, Color.WHITE, medium)
        shelfName.maxLines = 1
        val hint = label("OK drücken zum Öffnen", 28, 0xCCFFFFFF.toInt(), null)
        shelfTexts.addView(shelfName)
        shelfTexts.addView(hint, wrap(top = 18))
        shelf.addView(shelfTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        shelfImage = ImageView(this)
        shelfImage.scaleType = ImageView.ScaleType.CENTER_CROP
        shelfImage.background = Ui.round(0x33000000, 28f * k)
        shelfImage.outlineProvider = ViewOutlineProvider.BACKGROUND
        shelfImage.clipToOutline = true
        shelf.addView(shelfImage, LinearLayout.LayoutParams(px(498), px(280)))
        content.addView(shelf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(376)).apply { topMargin = px(36) })

        // Dock mit den Favoriten
        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.background = Ui.glass(38f * k, k)
        dock.setPadding(px(28), px(28), px(28), px(28))
        dock.clipChildren = false
        dock.clipToPadding = false
        content.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(44) })

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Untere Leiste
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.clipChildren = false
        bar.addView(pill("Alle Apps") { startActivity(Intent(this, AllAppsActivity::class.java)) })
        bar.addView(pill("Einstellungen") {
            try {
                HomeService.settingsUntil = SystemClock.uptimeMillis() + 3000
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }, wrap(left = 20))
        bar.addView(pill("Launcher einrichten") { startActivity(Intent(this, SetupActivity::class.java)) }, wrap(left = 20))
        content.addView(bar)

        setContentView(root)
    }

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val t = label(text, 24, Color.WHITE, null)
        t.setPadding(px(30), px(16), px(30), px(16))
        t.background = Ui.glass(999f, k)
        t.isFocusable = true
        t.setOnFocusChangeListener { v, has ->
            t.background = if (has) Ui.round(Color.WHITE, 999f) else Ui.glass(999f, k)
            t.setTextColor(if (has) Color.BLACK else Color.WHITE)
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(ease).start()
        }
        t.setOnClickListener { onClick() }
        return t
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.redirect(this) && Setup.canWrite(this) && !Setup.isEnabled(this)) Setup.enable(this)
        aurora.start()
        // sanftes Einblenden
        content.alpha = 0f
        content.scaleX = 0.975f
        content.scaleY = 0.975f
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(ease).start()
        loadFavs()
        if (System.currentTimeMillis() - lastWeather > 30 * 60 * 1000L) loadWeather()
    }

    override fun onPause() {
        aurora.stop()
        super.onPause()
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

    private fun showShelf(app: AppEntry) {
        if (shown == app.pkg) return
        shown = app.pkg
        val art = app.banner ?: app.icon
        shelfName.text = app.label
        shelfImage.setImageDrawable(art)
        shelfTexts.animate().cancel()
        shelfTexts.alpha = 0f
        shelfTexts.translationX = 28f * k
        shelfTexts.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(ease).start()
        shelfImage.animate().cancel()
        shelfImage.alpha = 0f
        shelfImage.scaleX = 0.94f
        shelfImage.scaleY = 0.94f
        shelfImage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(ease).start()
        aurora.setTint(Ui.avgColor(art, 0.34, 0xFF14141A.toInt()))
    }

    private fun showFavs(list: List<AppEntry>) {
        // Nichts neu aufbauen, wenn sich die Favoriten nicht geaendert haben
        if (dock.childCount > 0 && list.isNotEmpty() && list.map { it.pkg } == favApps.map { it.pkg }) return
        favApps = list
        dock.removeAllViews()
        if (list.isEmpty()) {
            shown = null
            shelfName.text = "Noch keine Favoriten"
            shelfImage.setImageDrawable(null)
            aurora.setTint(Ui.BG)
            dock.addView(label("Unter „Alle Apps“ eine App lange gedrückt halten, um sie hier anzupinnen.", 28, 0xD9FFFFFF.toInt(), null))
            return
        }
        val gap = px(32)
        val w = (resources.displayMetrics.widthPixels - px(192) - px(56) - gap * (Prefs.MAX_FAVS - 1)) / Prefs.MAX_FAVS
        val h = w * 9 / 16
        list.forEachIndexed { i, app ->
            val tile = Ui.tile(this, app, h, k) {
                lastFocus = i
                showShelf(app)
            }
            tile.setOnClickListener { v ->
                val opts = try {
                    ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
                } catch (e: Exception) {
                    null
                }
                Apps.launch(this, app, opts)
            }
            tile.setOnLongClickListener { editFav(app); true }
            tile.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { editFav(app); true } else false
            }
            dock.addView(tile, LinearLayout.LayoutParams(w, h).apply { if (i > 0) leftMargin = gap })
            // Kacheln nacheinander einblenden
            tile.translationY = 36f * k
            val target = tile.alpha
            tile.alpha = 0f
            tile.animate().translationY(0f).alpha(target).setStartDelay(60L * i).setDuration(300).setInterpolator(ease)
                .withEndAction { tile.animate().setStartDelay(0) }.start()
        }
        val idx = if (lastFocus < list.size) lastFocus else 0
        dock.getChildAt(idx)?.requestFocus()
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
