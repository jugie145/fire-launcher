package de.jugie.firelauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class AllAppsActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var grid: LinearLayout
    private lateinit var aurora: Aurora
    private lateinit var hint: TextView
    private val hintText = "OK öffnet die App  ·  OK lange halten: als Favorit anpinnen oder lösen"

    private fun px(v: Int): Int = (v * k).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = resources.displayMetrics.widthPixels / 1920f
        Ui.load(this)

        aurora = Aurora(this, k)
        aurora.configure(Prefs.bg(this), Prefs.bgAnim(this))
        val scroll = ScrollView(this)
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(px(120), px(72), px(120), px(72))
        col.clipChildren = false
        col.clipToPadding = false

        col.addView(Ui.text(this, "Alle Apps", 60f, k, Color.WHITE, Ui.light))
        hint = Ui.text(this, hintText, 23f, k, Ui.TEXT_DIM)
        col.addView(hint, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(12) })

        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.clipChildren = false
        grid.clipToPadding = false
        col.addView(grid, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(56) })

        scroll.addView(col)
        val frame = FrameLayout(this)
        frame.addView(aurora, FrameLayout.LayoutParams(match, match))
        frame.addView(scroll, FrameLayout.LayoutParams(match, match))
        setContentView(frame)

        thread {
            val all = Apps.loadAll(this)
            handler.post { show(all) }
        }
    }

    override fun onResume() {
        super.onResume()
        aurora.start()
    }

    override fun onPause() {
        aurora.stop()
        super.onPause()
    }

    private fun show(all: List<AppEntry>) {
        val cols = 7
        val size = px(164)
        val gap = (resources.displayMetrics.widthPixels - px(240) - size * cols) / (cols - 1)
        var line: LinearLayout? = null
        all.forEachIndexed { i, app ->
            if (i % cols == 0) {
                val l = LinearLayout(this)
                l.orientation = LinearLayout.HORIZONTAL
                l.clipChildren = false
                l.clipToPadding = false
                grid.addView(l, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(44) })
                line = l
            }
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.clipChildren = false
            cell.clipToPadding = false
            val tile = Ui.glassTile(this, app, size, k)
            tile.setOnClickListener { Apps.launch(this, app) }
            tile.setOnLongClickListener { toggleFav(app); true }
            cell.addView(tile, LinearLayout.LayoutParams(size, size))
            val name = Ui.text(this, app.label, 21f, k, Ui.TEXT_DIM)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            name.gravity = Gravity.CENTER_HORIZONTAL
            cell.addView(name, LinearLayout.LayoutParams(size, wrap).apply { topMargin = px(20) })
            line?.addView(cell, LinearLayout.LayoutParams(size, wrap).apply { if (i % cols > 0) leftMargin = gap })
            if (i < cols * 3) Ui.fadeIn(cell, 25L * i)
            if (i == 0) tile.requestFocus()
        }
    }

    private fun toggleFav(app: AppEntry) {
        val favs = ArrayList(Prefs.favs(this) ?: emptyList())
        val msg: String
        if (favs.contains(app.pkg)) {
            favs.remove(app.pkg)
            msg = app.label + " ist kein Favorit mehr."
        } else if (favs.size >= Prefs.MAX_FAVS) {
            msg = "Schon " + Prefs.MAX_FAVS + " Favoriten. Erst einen lösen."
        } else {
            favs.add(app.pkg)
            msg = app.label + " ist jetzt Favorit."
        }
        Prefs.setFavs(this, favs)
        hint.text = msg
        hint.setTextColor(Color.WHITE)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            hint.text = hintText
            hint.setTextColor(Ui.TEXT_DIM)
        }, 2600)
    }
}
