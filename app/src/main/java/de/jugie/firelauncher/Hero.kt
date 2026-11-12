package de.jugie.firelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Bild, das nach links weich ins Glas auslaeuft. Ohne Bild: neutraler Verlauf in der App-Farbe. */
class FadeImage(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var fallback = Ui.lastAccent
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun set(b: Bitmap?, color: Int) {
        bmp = b
        fallback = color
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mask = LinearGradient(0f, 0f, w * 0.42f, 0f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        val b = bmp
        val content: Shader = if (b != null) {
            val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val sc = Math.max(w / b.width, h / b.height)
            val m = Matrix()
            m.setScale(sc, sc)
            m.postTranslate((w - b.width * sc) / 2f, (h - b.height * sc) * 0.3f)
            s.setLocalMatrix(m)
            s
        } else {
            LinearGradient(0f, 0f, w, h, (fallback and 0x00FFFFFF) or 0x52000000, 0x14FFFFFF, Shader.TileMode.CLAMP)
        }
        paint.shader = ComposeShader(content, mask, PorterDuff.Mode.DST_IN)
    }

    override fun onDraw(c: Canvas) {
        if (paint.shader != null) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * Grosse Glasflaeche ueber der App-Leiste. Zeigt Inhalte passend zur ausgewaehlten App
 * und wechselt alle paar Sekunden zum naechsten Eintrag.
 */
class Hero(ctx: Context, private val k: Float) : FrameLayout(ctx) {

    private val handler = Handler(Looper.getMainLooper())
    private val imgA = FadeImage(ctx)
    private val imgB = FadeImage(ctx)
    private var front = imgA
    private val cover = ImageView(ctx)
    private val texts = LinearLayout(ctx)
    private val logo = ImageView(ctx)
    private val label: TextView
    private val title: TextView
    private val meta: TextView
    private val liveDot = LiveDot(ctx)
    private val desc: TextView
    private val dots = LinearLayout(ctx)
    private val source: TextView
    private var items: List<HeroItem> = emptyList()
    private var index = 0
    private var token = 0
    private var accent = Ui.lastAccent
    private var running = false
    private val next = Runnable { advance() }
    private var app: AppEntry? = null
    private var sourceText = ""
    private var dataLabel = ""
    private val lit = ImageView(ctx)

    // "Laeuft gerade" in gross, wenn Spotify ausgewaehlt ist und Musik laeuft
    private val npBox = LinearLayout(ctx)
    private val npCover = ImageView(ctx)
    private val npGlow = View(ctx)
    private lateinit var npEq: EqBars
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npAlbum: TextView
    private lateinit var npPos: TextView
    private lateinit var npLen: TextView
    private val npBarBg = View(ctx)
    private val npBar = View(ctx)
    private var npOn = false
    private var npUrl = ""
    private val npTick = object : Runnable {
        override fun run() {
            npProgress()
            if (npOn) handler.postDelayed(this, 1000)
        }
    }

    /** Farbe des gezeigten Bildes (fuer "Farben aus dem Inhalt"). */
    var onColor: ((Int) -> Unit)? = null

    /** Wird bei OK auf der Flaeche aufgerufen: App und der gerade gezeigte Eintrag. */
    var onOpen: ((AppEntry, HeroItem?) -> Unit)? = null

    private fun px(v: Int): Int = (v * k).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    init {
        clipChildren = false
        clipToPadding = false
        val glass = ImageView(ctx)
        glass.setImageResource(R.drawable.glass_hero)
        glass.scaleType = ImageView.ScaleType.FIT_XY
        addView(glass, LayoutParams(px(1768), px(468), Gravity.CENTER))
        lit.setImageResource(R.drawable.glass_hero_focus)
        lit.scaleType = ImageView.ScaleType.FIT_XY
        lit.alpha = 0f
        addView(lit, LayoutParams(px(1768), px(468), Gravity.CENTER))

        if (Theme.gta) {
            val tint = View(ctx)
            tint.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0x33FF3FA4, 0x9E1A082E.toInt(), 0x801A082E.toInt(), 0x1F50E1FF)
            ).apply { cornerRadius = 46f * k }
            addView(tint, LayoutParams(match, match))
            addView(EdgeGlow(ctx, k, 46f), LayoutParams(px(1760 + 40), px(460 + 40), Gravity.CENTER))
        }
        val clip = FrameLayout(ctx)
        clip.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, 46f * k)
            }
        }
        clip.clipToOutline = true
        imgB.alpha = 0f
        clip.addView(imgA, LayoutParams(px(1120), match, Gravity.END))
        clip.addView(imgB, LayoutParams(px(1120), match, Gravity.END))
        addView(clip, LayoutParams(match, match))

        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, 28f * k)
            }
        }
        cover.clipToOutline = true
        cover.alpha = 0f
        addView(cover, LayoutParams(px(340), px(340), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = px(110) })

        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.CENTER_VERTICAL
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        head.addView(logo, LinearLayout.LayoutParams(wrap, px(44)))
        label = Ui.text(ctx, "", 20f, k, Ui.TEXT_DIM, Ui.medium)
        label.letterSpacing = 0.14f
        head.addView(label, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = px(20) })
        texts.addView(head)
        title = Ui.text(ctx, "", 62f, k, Color.WHITE, Ui.medium)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setLineSpacing(0f, 1.05f)
        texts.addView(title, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(20) })
        meta = Ui.text(ctx, "", 26f, k, Ui.TEXT_DIM)
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        val metaRow = LinearLayout(ctx)
        metaRow.orientation = LinearLayout.HORIZONTAL
        metaRow.gravity = Gravity.CENTER_VERTICAL
        metaRow.clipChildren = false
        liveDot.visibility = View.GONE
        // Punkt samt Ring komplett innerhalb der Flaeche, nichts ragt nach links hinaus
        metaRow.addView(liveDot, LinearLayout.LayoutParams(px(38), px(38)).apply { rightMargin = px(4) })
        metaRow.addView(meta)
        texts.addView(metaRow, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(18) })
        desc = Ui.text(ctx, "", 25f, k, Ui.TEXT_DIM)
        desc.maxLines = 2
        desc.ellipsize = TextUtils.TruncateAt.END
        desc.setLineSpacing(0f, 1.3f)
        texts.addView(desc, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(16) })
        addView(texts, LayoutParams(px(820), match).apply { leftMargin = px(72); bottomMargin = px(30) })

        dots.orientation = LinearLayout.HORIZONTAL
        // keine Punkte mehr: es laufen immer 10 Titel, danach kommen 10 neue
        dots.visibility = View.GONE
        addView(dots, LayoutParams(wrap, wrap, Gravity.START or Gravity.BOTTOM).apply { leftMargin = px(72); bottomMargin = px(38) })
        source = Ui.text(ctx, "", 18f, k, Ui.TEXT_DIM)
        // keine Hinweise und keine Quellenangabe mehr (Quellen stehen in den Einstellungen)
        source.visibility = View.GONE
        addView(source, LayoutParams(wrap, wrap, Gravity.END or Gravity.BOTTOM).apply { rightMargin = px(44); bottomMargin = px(30) })
        if (Theme.gta) {
            title.typeface = Theme.bold
            title.isAllCaps = true
            Theme.sunsetText(title)
            label.setTextColor(Theme.CYAN)
            label.typeface = Theme.bold
            label.setShadowLayer(10f * k, 0f, 0f, 0xB346E1FF.toInt())
        }
        buildNowPlaying(ctx)

        // Mit "Hoch" anwaehlbar: links/rechts blaettert, OK oeffnet den gezeigten Eintrag
        isFocusable = true
        setOnFocusChangeListener { _, has ->
            lit.animate().alpha(if (has) 1f else 0f).setDuration(Ui.d(220)).start()
            updateSource()
            if (has) handler.removeCallbacks(next) else schedule()
        }
        setOnClickListener {
            val a = app
            if (a != null) onOpen?.invoke(a, if (npOn) null else items.getOrNull(index))
        }
        setOnKeyListener { _, code, ev ->
            if (npOn) {
                false
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (ev.action == KeyEvent.ACTION_DOWN && items.size > 1) {
                    index = (index + (if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1) + items.size) % items.size
                    render()
                }
                true
            } else {
                false
            }
        }
    }

    private fun updateSource() {
        if (source.visibility == View.GONE) return
        if (isFocused) {
            val link = items.getOrNull(index)?.link ?: ""
            val what = when {
                link.startsWith("https://www.youtube.com") -> "das Video"
                link.startsWith("https://www.twitch.tv") -> "den Stream"
                else -> "die App"
            }
            source.text = if (items.size > 1) "OK öffnet $what  ·  ◀ ▶ blättern" else "OK öffnet $what"
            source.setTextColor(Color.WHITE)
        } else {
            source.text = sourceText
            source.setTextColor(Ui.TEXT_DIM)
        }
    }

    /** Auf eine App umstellen: erst die App-Karte, dann (falls vorhanden) echte Inhalte. */
    fun show(app: AppEntry) {
        token++
        val my = token
        this.app = app
        nowPlaying(NowPlaying.track)
        accent = Ui.accentFor(app)
        val res = Ui.logoRes(app)
        if (res != 0) logo.setImageResource(res) else logo.setImageDrawable(app.icon ?: app.banner)
        val fallback = HeroData(
            app.label.uppercase(), "",
            listOf(HeroItem(app.label, Ui.descFor(app), "OK drücken zum Öffnen", emptyList()))
        )
        val k2 = Content.kind(app)
        if (k2 == "") {
            apply(fallback)
            return
        }
        var answered = false
        Content.load(app) { d ->
            if (my != token) return@load
            answered = true
            apply(d ?: fallback)
        }
        if (!answered) apply(fallback)
    }

    private fun apply(d: HeroData) {
        handler.removeCallbacks(next)
        items = d.items
        index = 0
        dataLabel = d.label
        sourceText = d.source
        updateSource()
        dots.removeAllViews()
        if (items.size > 1) {
            for (i in items.indices) {
                val v = View(context)
                v.background = GradientDrawable().apply { cornerRadius = 4f * k; setColor(0x59FFFFFF) }
                dots.addView(v, LinearLayout.LayoutParams(px(8), px(8)).apply { if (i > 0) leftMargin = px(10) })
            }
        }
        render()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(next)
        if (running && !npOn && items.size > 1 && !isFocused) handler.postDelayed(next, 6000)
    }

    private fun advance() {
        if (items.size < 2) return
        if (index + 1 >= items.size) {
            refill()
            return
        }
        index += 1
        render()
        schedule()
    }

    /** Nach den 10 Titeln eine neue zufaellige Auswahl holen (ohne den Fokus zu stoeren). */
    private fun refill() {
        val a = app ?: return
        val my = token
        Content.load(a) { d ->
            if (my != token) return@load
            if (d != null && d.items.isNotEmpty()) {
                items = d.items
                dataLabel = d.label
                sourceText = d.source
            }
            index = if (d != null && d.items.isNotEmpty()) 0 else (index + 1) % items.size
            render()
            schedule()
        }
    }

    private fun render() {
        if (items.isEmpty()) return
        val it = items[index]
        updateSource()
        label.text = (if (it.label.isNotBlank()) it.label else dataLabel).uppercase()
        title.text = it.title
        meta.text = it.meta
        meta.visibility = if (it.meta.isBlank()) View.GONE else View.VISIBLE
        liveDot.visibility = if (it.meta.uppercase().startsWith("LIVE")) View.VISIBLE else View.GONE
        desc.text = it.desc
        desc.visibility = if (it.desc.isBlank()) View.GONE else View.VISIBLE
        texts.animate().cancel()
        if (Ui.anims) {
            texts.alpha = 0f
            texts.translationX = 26f * k
            texts.animate().alpha(1f).translationX(0f).setDuration(420).setInterpolator(Ui.smooth).start()
        } else {
            texts.alpha = 1f
            texts.translationX = 0f
        }
        for (i in 0 until dots.childCount) {
            val v = dots.getChildAt(i)
            val on = i == index
            (v.background as GradientDrawable).setColor(if (on) 0xF2FFFFFF.toInt() else 0x59FFFFFF)
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.width = px(if (on) 30 else 8)
            v.layoutParams = lp
        }
        val my = token
        val at = index
        val ready = Images.cached(it.images)
        if (ready != null || it.images.isEmpty()) {
            image(it, ready)
        } else {
            image(it, null)
            Images.load(it.images) { b -> if (my == token && at == index && b != null) image(it, b) }
        }
        // naechstes Bild schon mal laden
        if (items.size > 1) Images.load(items[(index + 1) % items.size].images) { }
    }

    private fun image(item: HeroItem, b: Bitmap?) {
        val d = Ui.d(700)
        if (b != null && !npOn) onColor?.invoke(dominant(b))
        if (item.square) {
            swap(null, d)
            if (b != null) {
                cover.setImageBitmap(b)
                if (!npOn) cover.animate().alpha(1f).setDuration(d).start()
            } else {
                cover.animate().alpha(0f).setDuration(d).start()
            }
        } else {
            cover.animate().alpha(0f).setDuration(d).start()
            swap(b, d)
        }
    }

    private fun swap(b: Bitmap?, d: Long) {
        val back = if (front === imgA) imgB else imgA
        back.set(b, accent)
        if (npOn) {
            back.alpha = 0f
            front.alpha = 0f
            front = back
            return
        }
        back.animate().alpha(1f).setDuration(d).start()
        front.animate().alpha(0f).setDuration(d).start()
        front = back
    }

    fun start() {
        running = true
        schedule()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(next)
    }

    private fun buildNowPlaying(ctx: Context) {
        val accent = if (Theme.gta) 0xFFFF4FB0.toInt() else 0xFF1ED760.toInt()
        npGlow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 520f * k
            colors = intArrayOf((accent and 0x00FFFFFF) or 0x4D000000, (accent and 0x00FFFFFF) or 0x0F000000, accent and 0x00FFFFFF)
        }
        npGlow.alpha = 0f
        addView(npGlow, LayoutParams(px(1120), match, Gravity.END))
        npCover.scaleType = ImageView.ScaleType.CENTER_CROP
        npCover.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf((accent and 0x00FFFFFF) or 0x73000000, 0xE610141C.toInt()))
        npCover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 28f * k)
        }
        npCover.clipToOutline = true
        npCover.alpha = 0f
        addView(npCover, LayoutParams(px(340), px(340), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = px(110) })

        npBox.orientation = LinearLayout.VERTICAL
        npBox.gravity = Gravity.CENTER_VERTICAL
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(ctx)
        logo.setImageResource(R.drawable.logo_spotify)
        logo.adjustViewBounds = true
        head.addView(logo, LinearLayout.LayoutParams(wrap, px(40)))
        npEq = EqBars(ctx, accent)
        head.addView(npEq, LinearLayout.LayoutParams(px(35), px(22)).apply { leftMargin = px(20) })
        val lab = Ui.text(ctx, "LÄUFT GERADE", 20f, k, accent, if (Theme.gta) Theme.bold else Ui.medium)
        lab.letterSpacing = 0.15f
        head.addView(lab, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = px(20) })
        npBox.addView(head)
        npTitle = Ui.text(ctx, "", 66f, k, Color.WHITE, if (Theme.gta) Theme.bold else Ui.medium)
        npTitle.maxLines = 2
        npTitle.ellipsize = TextUtils.TruncateAt.END
        if (Theme.gta) {
            npTitle.isAllCaps = true
            Theme.sunsetText(npTitle)
        }
        npBox.addView(npTitle, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(16) })
        npArtist = Ui.text(ctx, "", 30f, k, 0xD9FFFFFF.toInt())
        npArtist.maxLines = 1
        npBox.addView(npArtist, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(16) })
        npAlbum = Ui.text(ctx, "", 24f, k, 0x99FFFFFF.toInt())
        npAlbum.maxLines = 1
        npBox.addView(npAlbum, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(12) })
        val prog = LinearLayout(ctx)
        prog.orientation = LinearLayout.HORIZONTAL
        prog.gravity = Gravity.CENTER_VERTICAL
        npPos = Ui.text(ctx, "0:00", 20f, k, 0xA6FFFFFF.toInt())
        prog.addView(npPos)
        val track = FrameLayout(ctx)
        npBarBg.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = 3f * k }
        track.addView(npBarBg, LayoutParams(match, match))
        npBar.background = if (Theme.gta) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.PINK, Theme.ORANGE)).apply { cornerRadius = 3f * k }
        else GradientDrawable().apply { setColor(accent); cornerRadius = 3f * k }
        track.addView(npBar, LayoutParams(0, match))
        prog.addView(track, LinearLayout.LayoutParams(px(560), px(6)).apply { leftMargin = px(18); rightMargin = px(18) })
        npLen = Ui.text(ctx, "0:00", 20f, k, 0xA6FFFFFF.toInt())
        prog.addView(npLen)
        npBox.addView(prog, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(26) })
        npBox.alpha = 0f
        npBox.visibility = View.GONE
        addView(npBox, LayoutParams(px(960), match).apply { leftMargin = px(72) })
    }

    /** Wechselt zwischen normalen Vorschlaegen und "Laeuft gerade" (nur bei Spotify). */
    fun nowPlaying(t: Spotify.Track?) {
        val want = t != null && app != null && Content.kind(app!!) == "spotify"
        if (t != null && want) {
            npTitle.text = t.title
            npArtist.text = t.artist
            npAlbum.text = if (t.album.isBlank()) "" else "Album: " + t.album
            npEq.playing = t.playing
            npLen.text = mmss(t.duration)
            if (t.cover != npUrl) {
                npUrl = t.cover
                if (t.cover.isNotBlank()) Images.load(listOf(t.cover)) { b -> if (b != null && npUrl == t.cover) npCover.setImageBitmap(b) }
            }
        }
        if (want == npOn) return
        npOn = want
        val d = Ui.d(500)
        if (want) {
            handler.removeCallbacks(next)
            npBox.visibility = View.VISIBLE
            npBox.animate().alpha(1f).setDuration(d).start()
            npCover.animate().alpha(1f).setDuration(d).start()
            npGlow.animate().alpha(1f).setDuration(d).start()
            for (v in listOf<View>(texts, cover, imgA, imgB)) v.animate().alpha(0f).setDuration(d).start()
            handler.post(npTick)
        } else {
            npBox.animate().alpha(0f).setDuration(d).withEndAction { if (!npOn) npBox.visibility = View.GONE }.start()
            npCover.animate().alpha(0f).setDuration(d).start()
            npGlow.animate().alpha(0f).setDuration(d).start()
            texts.animate().alpha(1f).setDuration(d).start()
            front.animate().alpha(1f).setDuration(d).start()
            handler.removeCallbacks(npTick)
            render()
            schedule()
        }
    }

    private fun npProgress() {
        val t = NowPlaying.track ?: return
        val pos = NowPlaying.position()
        npPos.text = mmss(pos)
        val w = npBarBg.width
        if (w > 0) {
            val lp = npBar.layoutParams
            lp.width = (w * pos.toFloat() / Math.max(1L, t.duration)).toInt()
            npBar.layoutParams = lp
        }
    }

    private fun mmss(ms: Long): String {
        val s = ms / 1000
        return "" + (s / 60) + ":" + String.format("%02d", s % 60)
    }

    /** Kraeftigste Farbe eines Bildes (grob abgetastet). */
    private fun dominant(b: Bitmap): Int {
        val hsv = FloatArray(3)
        var best = Ui.lastAccent
        var score = -1f
        val sx = Math.max(1, b.width / 24)
        val sy = Math.max(1, b.height / 14)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val sc = hsv[1] * hsv[2] * (if (hsv[2] > 0.25f) 1f else 0.2f)
                if (sc > score) {
                    score = sc
                    best = c
                }
                x += sx
            }
            y += sy
        }
        Color.colorToHSV(best, hsv)
        hsv[2] = Math.max(hsv[2], 0.8f)
        return Color.HSVToColor(hsv)
    }
}

/** Leuchtender Rand mit Verlauf Pink - Orange - Cyan (GTA-Design). */
class EdgeGlow(ctx: Context, private val k: Float, private val radius: Float) : View(ctx) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = android.graphics.RectF()

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val g = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Theme.PINK, 0xFFFF9A5B.toInt(), 0xFF5BE6FF.toInt()), null, Shader.TileMode.CLAMP)
        line.shader = g
        glow.shader = g
        glow.strokeWidth = 8f * k
        glow.maskFilter = android.graphics.BlurMaskFilter(10f * k, android.graphics.BlurMaskFilter.Blur.NORMAL)
        glow.alpha = 200
        line.strokeWidth = 2f * k
        val inset = 20f * k
        r.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(r, radius * k, radius * k, glow)
        c.drawRoundRect(r, radius * k, radius * k, line)
    }
}
