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
        texts.addView(meta, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(18) })
        desc = Ui.text(ctx, "", 25f, k, Ui.TEXT_DIM)
        desc.maxLines = 2
        desc.ellipsize = TextUtils.TruncateAt.END
        desc.setLineSpacing(0f, 1.3f)
        texts.addView(desc, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(16) })
        addView(texts, LayoutParams(px(820), match).apply { leftMargin = px(72); bottomMargin = px(30) })

        dots.orientation = LinearLayout.HORIZONTAL
        addView(dots, LayoutParams(wrap, wrap, Gravity.START or Gravity.BOTTOM).apply { leftMargin = px(72); bottomMargin = px(38) })
        source = Ui.text(ctx, "", 18f, k, Ui.TEXT_DIM)
        addView(source, LayoutParams(wrap, wrap, Gravity.END or Gravity.BOTTOM).apply { rightMargin = px(44); bottomMargin = px(30) })

        // Mit "Hoch" anwaehlbar: links/rechts blaettert, OK oeffnet den gezeigten Eintrag
        isFocusable = true
        setOnFocusChangeListener { _, has ->
            lit.animate().alpha(if (has) 1f else 0f).setDuration(Ui.d(220)).start()
            updateSource()
            if (has) handler.removeCallbacks(next) else schedule()
        }
        setOnClickListener {
            val a = app
            if (a != null) onOpen?.invoke(a, items.getOrNull(index))
        }
        setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
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
        if (running && items.size > 1 && !isFocused) handler.postDelayed(next, 6000)
    }

    private fun advance() {
        if (items.size < 2) return
        index = (index + 1) % items.size
        render()
        schedule()
    }

    private fun render() {
        if (items.isEmpty()) return
        val it = items[index]
        updateSource()
        label.text = (if (it.label.isNotBlank()) it.label else dataLabel).uppercase()
        title.text = it.title
        meta.text = it.meta
        meta.visibility = if (it.meta.isBlank()) View.GONE else View.VISIBLE
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
        if (item.square) {
            swap(null, d)
            if (b != null) {
                cover.setImageBitmap(b)
                cover.animate().alpha(1f).setDuration(d).start()
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
}
