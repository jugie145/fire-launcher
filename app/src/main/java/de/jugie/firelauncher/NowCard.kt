package de.jugie.firelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Vier Balken, die zur Musik huepfen. */
class EqBars(ctx: Context, color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val r = RectF()
    var playing = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width / 7f
        val t = SystemClock.uptimeMillis() / 1000.0
        val periods = doubleArrayOf(0.9, 1.1, 0.8, 1.0)
        for (i in 0 until 4) {
            val f = if (playing && Ui.anims) 0.3 + 0.7 * (0.5 + 0.5 * Math.sin((t + i * 0.17) * 2 * Math.PI / periods[i])) else 0.35
            val h = (height * f).toFloat()
            r.set(i * w * 2, height - h, i * w * 2 + w, height.toFloat())
            c.drawRoundRect(r, w / 2, w / 2, p)
        }
        if (playing && Ui.anims) postInvalidateOnAnimation()
    }
}

/** Gezeichnete Symbole: voriger, Pause/Play, naechster Titel. */
class MediaIcon(ctx: Context, private val kind: String) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    var playing = true
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = width / 24f
        p.style = Paint.Style.FILL
        path.reset()
        when (kind) {
            "prev" -> {
                c.drawRoundRect(5f * s, 5f * s, 7.2f * s, 19f * s, s, s, p)
                path.moveTo(19f * s, 5.5f * s); path.lineTo(19f * s, 18.5f * s); path.lineTo(9f * s, 12f * s); path.close()
            }
            "next" -> {
                c.drawRoundRect(16.8f * s, 5f * s, 19f * s, 19f * s, s, s, p)
                path.moveTo(5f * s, 5.5f * s); path.lineTo(5f * s, 18.5f * s); path.lineTo(15f * s, 12f * s); path.close()
            }
            else -> if (playing) {
                c.drawRoundRect(6.5f * s, 5f * s, 10.1f * s, 19f * s, 1.2f * s, 1.2f * s, p)
                c.drawRoundRect(13.9f * s, 5f * s, 17.5f * s, 19f * s, 1.2f * s, 1.2f * s, p)
            } else {
                path.moveTo(7.5f * s, 5f * s); path.lineTo(7.5f * s, 19f * s); path.lineTo(19.5f * s, 12f * s); path.close()
            }
        }
        c.drawPath(path, p)
    }
}

/**
 * Karte "Laeuft gerade" (640 x 104): Cover, huepfende Balken, Titel, Kuenstler, Fortschritt.
 * Ausgewaehlt: Glas in Spotify-Gruen (GTA: Pink-Orange), Lichtpunkt am Rand und Steuerung.
 * In der Leiste bleiben links/rechts Navigation; OK und die Mediatasten steuern.
 */
class NowCard(ctx: Context, private val k: Float, private val saver: Boolean = false) : FrameLayout(ctx) {
    private val off = ImageView(ctx)
    private val on = ImageView(ctx)
    private val comet: CometRing
    private val cover = ImageView(ctx)
    private val eq: EqBars
    private val title: TextView
    private val artist: TextView
    private val barBg = View(ctx)
    private val bar = View(ctx)
    private val controls = LinearLayout(ctx)
    private val toggle = MediaIcon(ctx, "toggle")
    private val textBox = LinearLayout(ctx)
    private var coverUrl = ""
    private val tick = object : Runnable {
        override fun run() {
            progress()
            if (isAttachedToWindow) postDelayed(this, 1000)
        }
    }

    private fun px(v: Int): Int = (v * k).toInt()

    init {
        clipChildren = false
        clipToPadding = false
        val accent = if (Theme.gta) 0xFFFF4FB0.toInt() else 0xFF1ED760.toInt()
        off.setImageResource(if (Theme.gta) R.drawable.glass_note_gta_off else R.drawable.glass_note_neutral)
        on.setImageResource(if (Theme.gta) R.drawable.glass_note_gta else R.drawable.glass_note_green)
        for (iv in listOf(off, on)) {
            iv.scaleType = ImageView.ScaleType.FIT_XY
            addView(iv, LayoutParams(px(740), px(204), Gravity.CENTER))
        }
        on.alpha = 0f
        comet = CometRing(ctx, k, if (Theme.gta) Theme.PINK else 0xFF1ED760.toInt(), 24f)
        comet.visibility = View.INVISIBLE
        addView(comet, LayoutParams(px(640 + 2 * CometRing.PAD), px(104 + 2 * CometRing.PAD), Gravity.CENTER))

        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf((accent and 0x00FFFFFF) or 0x80000000.toInt(), 0xE610141C.toInt())
        )
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 14f * k)
        }
        cover.clipToOutline = true
        addView(cover, LayoutParams(px(72), px(72)).apply { leftMargin = px(50 + 16); topMargin = px(50 + 16) })

        textBox.orientation = LinearLayout.VERTICAL
        textBox.gravity = Gravity.CENTER_VERTICAL
        val line = LinearLayout(ctx)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        eq = EqBars(ctx, accent)
        line.addView(eq, LinearLayout.LayoutParams(px(21), px(16)).apply { rightMargin = px(10) })
        title = Ui.text(ctx, "", 24f, k, Color.WHITE, if (Theme.gta) Theme.bold else Ui.medium)
        if (Theme.gta) title.isAllCaps = true
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        line.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        textBox.addView(line)
        artist = Ui.text(ctx, "", 19f, k, if (Theme.gta) Theme.PEACH else Ui.TEXT_DIM, if (Theme.gta) Theme.italic else null)
        artist.maxLines = 1
        artist.ellipsize = TextUtils.TruncateAt.END
        textBox.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        val track = FrameLayout(ctx)
        barBg.setBackgroundColor(0x33FFFFFF)
        track.addView(barBg, LayoutParams(-1, -1))
        bar.background = if (Theme.gta) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Theme.PINK, Theme.ORANGE))
        else GradientDrawable().apply { setColor(Color.WHITE) }
        track.addView(bar, LayoutParams(0, -1))
        textBox.addView(track, LinearLayout.LayoutParams(-1, px(3)).apply { topMargin = px(10) })
        addView(textBox, LayoutParams(px(500), px(104)).apply { leftMargin = px(50 + 108); topMargin = px(50) })

        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        for ((i, v) in listOf(MediaIcon(ctx, "prev"), toggle, MediaIcon(ctx, "next")).withIndex()) {
            val sz = if (i == 1) 48 else 40
            val box = FrameLayout(ctx)
            box.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == 1) 0x2EFFFFFF else 0x1AFFFFFF)
            }
            box.addView(v, LayoutParams(px(if (i == 1) 30 else 26), px(if (i == 1) 30 else 26), Gravity.CENTER))
            controls.addView(box, LinearLayout.LayoutParams(px(sz), px(sz)).apply { if (i > 0) leftMargin = px(10) })
        }
        controls.alpha = if (saver) 1f else 0f
        addView(controls, LayoutParams(-2, px(104), Gravity.END or Gravity.TOP).apply { rightMargin = px(50 + 20); topMargin = px(50) })

        if (!saver) {
            isFocusable = true
            setOnFocusChangeListener { _, has -> focus(has) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    private fun focus(has: Boolean) {
        val d = Ui.d(300)
        on.animate().alpha(if (has) 1f else 0f).setDuration(d).start()
        off.animate().alpha(if (has) 0f else 1f).setDuration(d).start()
        controls.animate().alpha(if (has) 1f else 0f).setDuration(d).start()
        val lp = textBox.layoutParams
        lp.width = px(if (has) 330 else 500)
        textBox.layoutParams = lp
        animate().translationY(if (has) -6f * k else 0f).scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f)
            .setDuration(d).setInterpolator(Ui.smooth).start()
        if (has) {
            comet.visibility = View.VISIBLE
            comet.start()
        } else {
            comet.stop()
            comet.visibility = View.INVISIBLE
        }
    }

    fun set(t: Spotify.Track?) {
        if (t == null) return
        title.text = t.title
        artist.text = if (t.playing) t.artist else "Pausiert · " + t.artist
        eq.playing = t.playing
        toggle.playing = t.playing
        if (t.cover != coverUrl) {
            coverUrl = t.cover
            cover.setImageDrawable(null)
            if (t.cover.isNotBlank()) Images.load(listOf(t.cover)) { b -> if (b != null && coverUrl == t.cover) cover.setImageBitmap(b) }
        }
        progress()
    }

    private fun progress() {
        val t = NowPlaying.track ?: return
        val w = barBg.width
        if (w <= 0) return
        val lp = bar.layoutParams
        lp.width = (w * (NowPlaying.position().toFloat() / Math.max(1L, t.duration))).toInt()
        bar.layoutParams = lp
    }
}
