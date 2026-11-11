package de.jugie.firelauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class AllAppsActivity : Activity() {

    private var k = 1f
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var grid: LinearLayout

    private fun px(v: Int): Int = (v * k).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = resources.displayMetrics.widthPixels / 1920f

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(px(96), px(64), px(96), px(64))
        col.clipChildren = false
        col.clipToPadding = false

        val title = TextView(this)
        title.text = "Alle Apps"
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 56 * k)
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        col.addView(title)
        val sub = TextView(this)
        sub.text = "OK öffnet die App · OK lange halten pinnt sie als Favorit an oder löst sie"
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * k)
        sub.setTextColor(Ui.MUTED)
        col.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })

        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.clipChildren = false
        grid.clipToPadding = false
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(40) })

        scroll.addView(col)
        setContentView(scroll)

        thread {
            val all = Apps.loadAll(this)
            handler.post { show(all) }
        }
    }

    private fun show(all: List<AppEntry>) {
        val cols = 6
        val gap = px(40)
        val w = (resources.displayMetrics.widthPixels - px(192) - gap * (cols - 1)) / cols
        val h = px(152)
        var line: LinearLayout? = null
        all.forEachIndexed { i, app ->
            if (i % cols == 0) {
                line = LinearLayout(this)
                line!!.orientation = LinearLayout.HORIZONTAL
                line!!.clipChildren = false
                line!!.clipToPadding = false
                grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(36) })
            }
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.clipChildren = false
            cell.clipToPadding = false
            val tile = Ui.tile(this, app, h, k)
            tile.setOnClickListener { Apps.launch(this, app) }
            tile.setOnLongClickListener { toggleFav(app); true }
            cell.addView(tile, LinearLayout.LayoutParams(w, h))
            val name = TextView(this)
            name.text = app.label
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * k)
            name.setTextColor(Ui.SOFT)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            name.gravity = Gravity.CENTER_HORIZONTAL
            cell.addView(name, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
            line!!.addView(cell, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i % cols > 0) leftMargin = gap })
            if (i == 0) tile.requestFocus()
        }
    }

    private fun toggleFav(app: AppEntry) {
        val favs = ArrayList(Prefs.favs(this) ?: emptyList())
        val msg: String
        if (favs.contains(app.pkg)) {
            favs.remove(app.pkg)
            msg = app.label + " ist kein Favorit mehr"
        } else if (favs.size >= Prefs.MAX_FAVS) {
            msg = "Schon " + Prefs.MAX_FAVS + " Favoriten – erst einen entfernen"
        } else {
            favs.add(app.pkg)
            msg = app.label + " ist jetzt Favorit"
        }
        Prefs.setFavs(this, favs)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
