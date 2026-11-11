package de.jugie.firelauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private data class CinemaItem(
    val title: String,
    val meta: String,
    val description: String,
    val poster: String,
    val backdrop: String,
    val trailer: String,
    val provider: String
)

class CinemaActivity : Activity() {
    private var scale = 1f
    private lateinit var player: WebView
    private lateinit var backdrop: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var description: TextView
    private lateinit var status: TextView
    private lateinit var strip: LinearLayout
    private val items = ArrayList<CinemaItem>()
    private var selected = -1

    private fun px(v: Int) = (v * scale).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scale = resources.displayMetrics.widthPixels / 1920f
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        build()
        loadContent()
    }

    private lateinit var wave: WaveBackground

    private fun build() {
        Ui.load(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        wave = WaveBackground(this)
        root.addView(wave, FrameLayout.LayoutParams(match, match))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(80), px(42), px(80), px(30))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(content, FrameLayout.LayoutParams(match, match))

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(Ui.text(this, "LIQUID", 32f, scale, Color.WHITE, Ui.medium))
        header.addView(Ui.text(this, " CINEMA", 32f, scale, 0xFF82DDF2.toInt(), Ui.medium))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(Ui.text(this, "▼  ZURÜCK", 19f, scale, Ui.TEXT_DIM))
        content.addView(header, LinearLayout.LayoutParams(match, px(48)))

        val hero = FrameLayout(this).apply {
            clipChildren = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, px(34).toFloat())
                }
            }
            clipToOutline = true
        }
        val glass = ImageView(this).apply {
            setImageResource(R.drawable.glass_hero)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        hero.addView(glass, FrameLayout.LayoutParams(match, match))
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        hero.addView(backdrop, FrameLayout.LayoutParams(px(1120), match, Gravity.END))
        player = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = WebChromeClient()
            isFocusable = false
            visibility = View.GONE
        }
        hero.addView(player, FrameLayout.LayoutParams(px(1120), match, Gravity.END))
        val shade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF0080A12.toInt(), 0xD0080A12.toInt(), 0x00080A12)
            )
        }
        hero.addView(shade, FrameLayout.LayoutParams(match, match))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(66), px(28), px(30), px(34))
        }
        info.addView(Ui.text(this, "JETZT ENTDECKEN", 18f, scale, 0xFF82DDF2.toInt(), Ui.medium))
        title = Ui.text(this, "Dein Kino. Direkt auf dem Startbildschirm.", 54f, scale, Color.WHITE, Ui.medium)
        title.maxLines = 2
        info.addView(title, LinearLayout.LayoutParams(px(800), wrap).apply { topMargin = px(16) })
        meta = Ui.text(this, "FILME  ·  SERIEN  ·  TRAILER", 23f, scale, Ui.TEXT_DIM)
        info.addView(meta, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(18) })
        description = Ui.text(this, "Trailer starten automatisch, sobald du einen Titel auswählst.", 21f, scale, 0xDFFFFFFF.toInt())
        description.maxLines = 3
        info.addView(description, LinearLayout.LayoutParams(px(720), wrap).apply { topMargin = px(14) })
        info.addView(Ui.text(this, "OK  Trailer abspielen", 18f, scale, Ui.TEXT_DIM),
            LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(26) })
        hero.addView(info, FrameLayout.LayoutParams(match, match))
        content.addView(hero, LinearLayout.LayoutParams(match, px(450)).apply { topMargin = px(26) })

        val rowHead = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        rowHead.addView(Ui.text(this, "FÜR DICH ENTDECKT", 24f, scale, Color.WHITE, Ui.medium))
        rowHead.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        status = Ui.text(this, "INHALTE VON TMDB", 16f, scale, Ui.TEXT_FAINT)
        rowHead.addView(status)
        content.addView(rowHead, LinearLayout.LayoutParams(match, px(38)).apply { topMargin = px(28) })

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFocusable = false
        }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(strip, ViewGroup.LayoutParams(wrap, wrap))
        content.addView(scroll, LinearLayout.LayoutParams(match, px(220)).apply { topMargin = px(14) })
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(Ui.text(this, "LIQUID GLASS  ·  BACK ZUM STARTBILDSCHIRM", 16f, scale, Ui.TEXT_FAINT),
            LinearLayout.LayoutParams(match, px(28)))
        setContentView(root)
    }

    private fun loadContent() {
        status.text = "Filme und Serien werden geladen …"
        thread {
            try {
                val c = URL("https://raw.githubusercontent.com/jugie145/fire-launcher/data/content.json")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 12000
                val json = c.inputStream.bufferedReader().use { it.readText() }
                val data = JSONObject(json)
                val providers = listOf("netflix" to "NETFLIX", "prime" to "PRIME VIDEO", "wow" to "WOW", "disney" to "DISNEY+")
                val loaded = ArrayList<CinemaItem>()
                for ((key, label) in providers) {
                    val section = data.optJSONObject(key) ?: continue
                    val arr = section.optJSONArray("items") ?: continue
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val images = o.optJSONArray("images")
                        val backdropUrl = if (images != null && images.length() > 0) images.optString(0) else ""
                        val titleText = o.optString("title", "")
                        if (titleText.isBlank()) continue
                        loaded.add(CinemaItem(
                            titleText, o.optString("meta", ""), o.optString("desc", ""),
                            o.optString("poster", ""), backdropUrl, o.optString("trailer", ""), label
                        ))
                    }
                }
                runOnUiThread {
                    items.clear()
                    items.addAll(loaded)
                    renderItems()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Inhalte gerade nicht erreichbar" }
            }
        }
    }

    private fun renderItems() {
        strip.removeAllViews()
        if (items.isEmpty()) {
            status.text = "Noch keine Filmdaten vorhanden"
            return
        }
        status.text = "${items.size} Titel · TMDB"
        items.take(16).forEachIndexed { index, item ->
            val card = FrameLayout(this).apply {
                isFocusable = true
                isClickable = true
                clipChildren = false
                clipToPadding = false
                background = GradientDrawable().apply {
                    cornerRadius = px(22).toFloat()
                    setColor(0x33FFFFFF)
                    setStroke(px(1), 0x55FFFFFF)
                }
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = px(17).toFloat()
                    setColor(0xFF1C2434.toInt())
                }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, px(17).toFloat())
                    }
                }
                clipToOutline = true
            }
            card.addView(image, FrameLayout.LayoutParams(match, match).apply {
                setMargins(px(6), px(6), px(6), px(6))
            })
            val shade = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xEE080A12.toInt(), 0x00080A12)
                )
            }
            card.addView(shade, FrameLayout.LayoutParams(match, px(88), Gravity.BOTTOM).apply {
                setMargins(px(6), 0, px(6), px(6))
            })
            val provider = Ui.text(this, item.provider, 12f, scale, 0xFF82DDF2.toInt(), Ui.medium)
            card.addView(provider, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = px(17); bottomMargin = px(40)
            })
            val label = Ui.text(this, item.title, 17f, scale, Color.WHITE, Ui.medium)
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            card.addView(label, FrameLayout.LayoutParams(px(170), wrap, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = px(17); bottomMargin = px(16)
            })
            val imageUrl = if (item.poster.isNotBlank()) item.poster else item.backdrop
            if (imageUrl.isNotBlank()) Images.load(listOf(imageUrl)) { bitmap ->
                if (bitmap != null) image.setImageBitmap(bitmap)
            }
            card.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f)
                    .translationY(if (focused) -px(5).toFloat() else 0f)
                    .setDuration(Ui.d(180)).start()
                if (focused) select(index)
            }
            card.setOnClickListener { select(index) }
            card.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    finish()
                    true
                } else false
            }
            strip.addView(card, LinearLayout.LayoutParams(px(190), px(210)).apply {
                if (index > 0) leftMargin = px(20)
            })
            if (index == 0) card.post { card.requestFocus() }
        }
        val firstWithTrailer = items.indexOfFirst { it.trailer.isNotBlank() }
        select(if (firstWithTrailer >= 0) firstWithTrailer else 0)
    }

    private fun select(index: Int) {
        if (index !in items.indices || selected == index) return
        selected = index
        val item = items[index]
        title.text = item.title
        meta.text = item.provider + "   ·   " + item.meta
        description.text = item.description.ifBlank { "Entdecke den Titel bei deinem Streaming-Anbieter." }
        player.stopLoading()
        player.visibility = View.GONE
        if (item.backdrop.isNotBlank()) {
            Images.load(listOf(item.backdrop)) { bitmap ->
                if (selected == index && bitmap != null) backdrop.setImageBitmap(bitmap)
            }
        }
        if (item.trailer.isNotBlank()) {
            val id = android.net.Uri.parse(item.trailer).getQueryParameter("v")
            if (!id.isNullOrBlank()) {
                player.visibility = View.VISIBLE
                val html = """
                    <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>html,body{margin:0;padding:0;background:#000;width:100%;height:100%;overflow:hidden}
                    iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
                    <body><iframe src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&mute=1&controls=0&rel=0&playsinline=1&modestbranding=1"
                    allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe></body></html>
                """.trimIndent()
                player.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onResume() {\n        super.onResume()\n        if (::wave.isInitialized) wave.start()\n    }\n\n    override fun onPause() {\n        if (::wave.isInitialized) wave.stop()\n        super.onPause()\n    }\n\n    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            player.stopLoading()
            player.loadUrl("about:blank")
            player.destroy()
        }
        super.onDestroy()
    }
}
