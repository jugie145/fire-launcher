package de.jugie.firelauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.Executors

/** Suchindex (Filme und Serien bei Netflix, Prime Video, WOW, Disney+, im Kino), von GitHub einmal am Tag erzeugt. */
object SearchIndex {
    class Entry(val title: String, val orig: String, val year: String, val kind: String, val providers: List<String>, val img: String, val nf: String = "") {
        val key = norm(title) + " " + norm(orig)
    }

    private const val URL_INDEX = "https://raw.githubusercontent.com/jugie145/fire-launcher/data/search.json"
    @Volatile private var items: List<Entry> = emptyList()
    private var at = 0L
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace("ß", "ss")

    fun load(cb: (List<Entry>) -> Unit) {
        if (items.isNotEmpty() && System.currentTimeMillis() - at < 6 * 3600_000L) {
            cb(items)
            return
        }
        pool.execute {
            try {
                val c = URL(URL_INDEX).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 15000
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(txt).optJSONArray("items")
                val out = ArrayList<Entry>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = o.optJSONArray("p")
                    out.add(Entry(o.optString("t"), o.optString("o"), o.optString("y"), o.optString("k"),
                        (0 until (p?.length() ?: 0)).map { p!!.getString(it) }, o.optString("img"), o.optString("nf")))
                }
                if (out.isNotEmpty()) {
                    items = out
                    at = System.currentTimeMillis()
                }
            } catch (e: Throwable) {
            }
            main.post { cb(items) }
        }
    }

    fun find(q: String): List<Entry> {
        val n = norm(q.trim())
        if (n.isEmpty()) return items.take(12)
        val starts = ArrayList<Entry>()
        val inside = ArrayList<Entry>()
        for (e in items) {
            if (e.key.startsWith(n) || e.key.contains(" $n")) starts.add(e)
            else if (e.key.contains(n)) inside.add(e)
            if (starts.size >= 12) break
        }
        return (starts + inside).take(12)
    }
}

/**
 * Suche nach Filmen und Serien. Das Suchfeld ist sofort aktiv, die Tastatur des Fire TV geht von
 * selbst auf. Treffer zeigen, wo der Titel laeuft; OK sucht direkt in der passenden App.
 */
class SearchOverlay(ctx: Context, private val k: Float, private val apps: () -> List<AppEntry>) : FrameLayout(ctx) {
    private val field = EditText(ctx)
    private val head: TextView
    private val grid = LinearLayout(ctx)
    private val rows = listOf(LinearLayout(ctx), LinearLayout(ctx))
    private val none = LinearLayout(ctx)
    private val panel = FrameLayout(ctx)
    private val bar = FrameLayout(ctx)
    private val barBg = GradientDrawable()
    var onClose: (() -> Unit)? = null
    var showing = false
        private set
    private var kb = true

    private fun px(v: Int): Int = (v * k).toInt()

    private val PROV = mapOf(
        "Netflix" to Color.rgb(229, 9, 20), "Prime Video" to Color.rgb(40, 150, 211), "WOW" to Color.rgb(2, 170, 178),
        "Disney+" to Color.rgb(49, 186, 221), "Im Kino" to Color.rgb(232, 180, 80)
    )
    private val KIND = mapOf("Netflix" to "netflix", "Prime Video" to "prime", "WOW" to "wow", "Disney+" to "disney")

    init {
        visibility = View.GONE
        isClickable = true
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 1300f * k
            setGradientCenter(0.5f, 0.3f)
            // undurchsichtig: vom Startbildschirm ist nichts mehr zu sehen
            colors = if (Theme.gta) intArrayOf(0xFF2A1240.toInt(), 0xFF12061F.toInt()) else intArrayOf(0xFF161C2C.toInt(), 0xFF080A12.toInt())
        }
        clipChildren = false
        addView(panel, LayoutParams(px(1760), px(952)).apply { leftMargin = px(80); topMargin = px(64) })

        barBg.cornerRadius = 48f * k
        barBg.colors = intArrayOf(0x29FFFFFF, 0x0FFFFFFF)
        barBg.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        bar.background = barBg
        val icon = ImageView(ctx)
        icon.setImageResource(R.drawable.ic_search)
        bar.addView(icon, LayoutParams(px(40), px(40), Gravity.CENTER_VERTICAL).apply { leftMargin = px(36) })
        field.background = null
        field.setTextColor(Color.WHITE)
        field.setHintTextColor(0x80FFFFFF.toInt())
        field.hint = "Filme und Serien suchen"
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 38f * k)
        field.isSingleLine = true
        field.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        field.setPadding(0, 0, 0, 0)
        if (Theme.gta) field.typeface = Theme.bold
        bar.addView(field, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = px(98); rightMargin = px(40) })
        panel.addView(bar, LayoutParams(-1, px(96)))
        field.setOnFocusChangeListener { _, has -> styleBar(has) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        field.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            (rows[0].getChildAt(0))?.requestFocus()
            true
        }

        head = Ui.text(ctx, "", 24f, k, 0xB3FFFFFF.toInt(), if (Theme.gta) Theme.bold else Ui.medium)
        head.letterSpacing = 0.08f
        if (Theme.gta) head.setTextColor(Theme.CYAN)
        grid.orientation = LinearLayout.VERTICAL
        grid.addView(head)
        for ((i, r) in rows.withIndex()) {
            r.orientation = LinearLayout.HORIZONTAL
            r.clipChildren = false
            r.clipToPadding = false
            // etwas Abstand zum Rand, damit vergroesserte Poster ganz zu sehen sind
            r.setPadding(px(37), 0, px(37), 0)
            grid.addView(r, LinearLayout.LayoutParams(-1, px(366)).apply { topMargin = px(if (i == 0) 22 else 30) })
        }
        none.orientation = LinearLayout.VERTICAL
        none.visibility = View.GONE
        grid.addView(none, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        grid.clipChildren = false
        panel.clipChildren = false
        panel.addView(grid, LayoutParams(-1, -2).apply { topMargin = px(140) })

        // Tastatur auf/zu: bei offener Tastatur nur eine Reihe Treffer
        setOnApplyWindowInsetsListener { v, insets ->
            val open = if (android.os.Build.VERSION.SDK_INT >= 30) insets.isVisible(WindowInsets.Type.ime()) else kb
            if (open != kb) {
                kb = open
                rows[1].visibility = if (open) View.GONE else View.VISIBLE
            }
            v.onApplyWindowInsets(insets)
        }
    }

    private fun styleBar(focused: Boolean) {
        barBg.setStroke(Math.max(1, (1.5f * k).toInt()), if (focused) 0xBFFFFFFF.toInt() else 0x52FFFFFF)
    }

    fun open() {
        showing = true
        visibility = View.VISIBLE
        alpha = 0f
        panel.translationY = 40f * k
        animate().alpha(1f).setDuration(Ui.d(360)).start()
        panel.animate().translationY(0f).setDuration(Ui.d(520)).setInterpolator(android.view.animation.PathInterpolator(0.2f, 1.1f, 0.35f, 1f)).start()
        field.setText("")
        refresh()
        SearchIndex.load { refresh() }
        field.requestFocus()
        field.postDelayed({ showKeyboard() }, 120)
    }

    fun close() {
        if (!showing) return
        showing = false
        hideKeyboard()
        animate().alpha(0f).setDuration(Ui.d(260)).withEndAction { if (!showing) visibility = View.GONE }.start()
        onClose?.invoke()
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun refresh() {
        val q = field.text?.toString() ?: ""
        val hits = SearchIndex.find(q)
        head.text = if (q.isBlank()) "BELIEBT GERADE" else if (hits.isEmpty()) "" else "${hits.size} TREFFER"
        for (r in rows) r.removeAllViews()
        hits.forEachIndexed { i, e ->
            val r = rows[i / 6]
            r.addView(card(e, i), LinearLayout.LayoutParams(px(256), -1).apply { if (i % 6 > 0) leftMargin = px(30) })
        }
        none.removeAllViews()
        none.visibility = if (q.isNotBlank() && hits.isEmpty()) View.VISIBLE else View.GONE
        if (none.visibility == View.VISIBLE) {
            none.addView(Ui.text(context, "Nichts gefunden. Direkt in der App weitersuchen:", 26f, k, 0xBFFFFFFF.toInt()))
            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL
            for (p in listOf("Netflix", "Prime Video", "WOW", "Disney+")) {
                val t = Ui.pill(context, p, k) { openIn(p, q) }
                line.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(16) })
            }
            none.addView(line, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(22) })
        }
    }

    private fun card(e: SearchIndex.Entry, i: Int): View {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.clipChildren = false
        val poster = FrameLayout(context)
        val c0 = PROV[e.providers.firstOrNull() ?: ""] ?: Color.GRAY
        poster.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf((c0 and 0x00FFFFFF) or 0x8C000000.toInt(), 0xEB0E1018.toInt())).apply {
            cornerRadius = 22f * k
            setStroke(Math.max(1, (1.5f * k).toInt()), 0x47FFFFFF)
        }
        poster.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 22f * k)
        }
        poster.clipToOutline = true
        val img = ImageView(context)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.addView(img, LayoutParams(-1, -1))
        if (e.img.isNotBlank()) Images.load(listOf(e.img)) { b -> if (b != null) img.setImageBitmap(b) }
        val chips = LinearLayout(context)
        chips.orientation = LinearLayout.HORIZONTAL
        for (p in e.providers.take(2)) {
            val t = Ui.text(context, p, 15f, k, Color.WHITE, Ui.medium)
            t.setPadding(px(10), px(4), px(10), px(4))
            t.background = GradientDrawable().apply {
                cornerRadius = 99f * k
                setColor(0xB3080A12.toInt())
                setStroke(Math.max(1, k.toInt()), PROV[p] ?: Color.WHITE)
            }
            chips.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(6) })
        }
        poster.addView(chips, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = px(14); bottomMargin = px(14) })
        // Rahmen wie bei den App-Kacheln: Lichtpunkt laeuft um das ausgewaehlte Poster
        val pw = FrameLayout(context)
        pw.clipChildren = false
        pw.addView(poster, LayoutParams(-1, -1))
        val ring = CometRing(context, k, if (Theme.gta) Theme.PINK else c0, 22f)
        ring.visibility = View.INVISIBLE
        pw.addView(ring, LayoutParams(px(256 + 2 * CometRing.PAD), px(300 + 2 * CometRing.PAD)).apply {
            leftMargin = -px(CometRing.PAD)
            topMargin = -px(CometRing.PAD)
        })
        box.addView(pw, LinearLayout.LayoutParams(-1, px(300)))
        val t = Ui.text(context, e.title, 22f, k, Color.WHITE, Ui.medium)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        box.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        box.addView(Ui.text(context, listOf(e.year, e.kind).filter { it.isNotBlank() }.joinToString(" · "), 18f, k, 0x99FFFFFF.toInt()),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2) })
        box.isFocusable = true
        box.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).translationY(if (has) -6f * k else 0f).setDuration(Ui.d(200)).setInterpolator(Ui.smooth).start()
            (poster.background as GradientDrawable).setStroke(Math.max(1, (1.5f * k).toInt()), if (has) 0x00FFFFFF else 0x47FFFFFF)
            if (has) {
                ring.visibility = View.VISIBLE
                ring.start()
            } else {
                ring.stop()
                ring.visibility = View.INVISIBLE
            }
        }
        box.setOnClickListener { open(e) }
        if (Ui.anims) {
            box.alpha = 0f
            box.translationY = 18f * k
            box.animate().alpha(1f).translationY(0f).setStartDelay(i * 45L).setDuration(420).setInterpolator(Ui.smooth).start()
        }
        return box
    }

    /** In der ersten installierten App suchen, in der der Titel laeuft. */
    private fun open(e: SearchIndex.Entry) {
        for (p in e.providers) if (openIn(p, e.title, if (p == "Netflix" && e.nf.isNotBlank()) "https://www.netflix.com/title/" + e.nf else "")) return
    }

    private fun openIn(provider: String, title: String, link: String = ""): Boolean {
        val kind = KIND[provider] ?: return false
        val app = apps().firstOrNull { Content.kind(it) == kind } ?: return false
        if (!Apps.openLink(context, app, Content.links(kind, title, link))) Apps.launch(context, app)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && showing) {
            close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
