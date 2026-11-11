package de.jugie.firelauncher

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.Property
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.concurrent.thread

/**
 * Abstrakter Hintergrund: dunkle Grundflaeche, weiche Farbformen (links in der Akzentfarbe der
 * ausgewaehlten App, rechts in der festen Farbwelt), darueber fliessende Wellen mit feinen
 * Lichtkanten und eine Vignette. Die Wellen werden einmal als Bild berechnet; bewegt werden nur
 * fertige GPU-Ebenen, deshalb bleibt es auf dem Stick fluessig.
 */
class Aurora(ctx: Context, private val k: Float) : FrameLayout(ctx) {

    companion object {
        val NAMES = arrayOf("Aurora", "Ozean", "Violett", "Mitternacht")
        @Volatile private var waves: Bitmap? = null
    }

    private val anims = ArrayList<Animator>()
    private var animated = true
    private var running = false
    private val boxes = ArrayList<FrameLayout>()
    private val boxBlobs = ArrayList<ArrayList<View>>()
    private var active = 0
    private var accent = 0
    private var waveView: ImageView? = null
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun darker(c: Int): Int =
        Color.rgb((Color.red(c) * 0.5f).toInt(), (Color.green(c) * 0.5f).toInt(), (Color.blue(c) * 0.5f).toInt())

    fun configure(preset: Int, animate: Boolean, accentColor: Int) {
        for (a in anims) a.cancel()
        anims.clear()
        removeAllViews()
        boxes.clear()
        boxBlobs.clear()
        animated = animate
        accent = accentColor
        active = 0
        setBackgroundColor(Ui.MIDNIGHT)
        val dim = if (preset == 3) 0.55f else 1f

        blob(this, Ui.NAVY, 0.7f, 960f, 540f, 1400f, 800f, 0f, 0f, 90000L)
        for (g in 0..1) {
            val box = FrameLayout(context)
            box.alpha = if (g == 0) 1f else 0f
            addView(box, LayoutParams(match, match))
            val list = ArrayList<View>()
            list.add(blob(box, accent, 0.82f * dim, 80f, 460f, 520f, 420f, 150f, -70f, 52000L))
            list.add(blob(box, darker(accent), 0.5f * dim, 760f, 1090f, 540f, 250f, 220f, -40f, 64000L))
            boxes.add(box)
            boxBlobs.add(list)
        }
        val c = when (preset) {
            1 -> intArrayOf(Ui.OCEAN, Ui.OCEAN, Ui.CYAN)
            2 -> intArrayOf(Ui.VIOLET, Ui.VIOLET, Ui.OCEAN)
            3 -> intArrayOf(Ui.NAVY, Ui.OCEAN, Ui.VIOLET)
            else -> intArrayOf(Ui.OCEAN, Ui.VIOLET, Ui.CYAN)
        }
        blob(this, c[0], 0.72f * dim, 1400f, 720f, 600f, 380f, -160f, -60f, 58000L)
        blob(this, c[1], 0.86f * dim, 1860f, 460f, 520f, 400f, -120f, 90f, 47000L)
        blob(this, c[2], 0.14f, 1280f, 1010f, 400f, 160f, 180f, -30f, 69000L)

        val wv = ImageView(context)
        wv.scaleType = ImageView.ScaleType.FIT_XY
        wv.scaleX = 1.08f
        wv.scaleY = 1.08f
        addView(wv, LayoutParams(match, match))
        waveView = wv
        loop(wv, View.TRANSLATION_X, -34f * k, 34f * k, 61000L)
        loop(wv, View.TRANSLATION_Y, 16f * k, -16f * k, 43000L)
        loadWaves(wv)

        val vig = View(context)
        val vd = GradientDrawable()
        vd.gradientType = GradientDrawable.RADIAL_GRADIENT
        vd.gradientRadius = 1300f * k
        vd.colors = intArrayOf(0x00000000, 0x00000000, 0xA6000000.toInt())
        vig.background = vd
        addView(vig, LayoutParams(match, match))
        if (running) start()
    }

    /** Akzentfarbe weich ueberblenden (z. B. rot bei YouTube, gruen bei Spotify). */
    fun setAccent(c: Int) {
        if (c == accent || boxes.size < 2) return
        accent = c
        val next = 1 - active
        val list = boxBlobs[next]
        (list[0].background as GradientDrawable).colors = intArrayOf(c, c and 0x00FFFFFF)
        val dk = darker(c)
        (list[1].background as GradientDrawable).colors = intArrayOf(dk, dk and 0x00FFFFFF)
        list[0].invalidate()
        list[1].invalidate()
        boxes[next].animate().alpha(1f).setDuration(Ui.d(700)).start()
        boxes[active].animate().alpha(0f).setDuration(Ui.d(700)).start()
        active = next
    }

    /** Weiche Farbform; Koordinaten und Radien in 1920x1080-Einheiten. */
    private fun blob(
        parent: FrameLayout, c: Int, a: Float, cx: Float, cy: Float, rx: Float, ry: Float,
        dx: Float, dy: Float, dur: Long
    ): View {
        val base = (600 * k).toInt()
        val v = View(context)
        v.background = Ui.radial(c, base)
        v.alpha = a
        val tx = cx * k - base / 2f
        val ty = cy * k - base / 2f
        val sx = rx / 200f
        val sy = ry / 200f
        v.translationX = tx
        v.translationY = ty
        v.scaleX = sx
        v.scaleY = sy
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        parent.addView(v, LayoutParams(base, base))
        if (dx != 0f || dy != 0f) {
            loop(v, View.TRANSLATION_X, tx, tx + dx * k, dur)
            loop(v, View.TRANSLATION_Y, ty, ty + dy * k, (dur * 1.31f).toLong())
            loop(v, View.SCALE_X, sx, sx * 1.14f, (dur * 0.83f).toLong())
            loop(v, View.SCALE_Y, sy, sy * 1.1f, (dur * 0.97f).toLong())
        }
        return v
    }

    private fun loop(v: View, prop: Property<View, Float>, from: Float, to: Float, dur: Long) {
        val a = ObjectAnimator.ofFloat(v, prop, from, to)
        a.duration = dur
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.interpolator = AccelerateDecelerateInterpolator()
        anims.add(a)
    }

    private fun loadWaves(target: ImageView) {
        val cached = waves
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }
        thread {
            val b = try { makeWaves() } catch (e: Throwable) { null }
            if (b != null) {
                waves = b
                post {
                    val wv = waveView ?: return@post
                    wv.setImageBitmap(b)
                    if (Ui.anims) {
                        wv.alpha = 0f
                        wv.animate().alpha(1f).setDuration(900).start()
                    }
                }
            }
        }
    }

    /** Fliessende Wellen: Schatten ueber jeder Kante, weicher Schein darunter, feine helle Linie. */
    private fun makeWaves(): Bitmap {
        val w = 960
        val h = 540
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val curves = arrayOf(
            floatArrayOf(-0.05f, 0.47f, 0.20f, 0.30f, 0.36f, 0.44f, 0.54f, 0.14f, 0.64f, -0.03f, 0.85f, -0.10f, 1.05f, -0.12f),
            floatArrayOf(-0.05f, 0.70f, 0.22f, 0.52f, 0.40f, 0.86f, 0.66f, 0.58f, 0.80f, 0.43f, 0.93f, 0.33f, 1.05f, 0.27f),
            floatArrayOf(-0.05f, 1.02f, 0.20f, 0.98f, 0.34f, 0.80f, 0.52f, 0.90f, 0.72f, 1.00f, 0.90f, 0.70f, 1.05f, 0.62f),
            floatArrayOf(0.30f, -0.10f, 0.52f, 0.16f, 0.66f, 0.36f, 0.82f, 0.30f, 0.92f, 0.26f, 1.00f, 0.14f, 1.05f, 0.10f)
        )
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(205, 0, 0, 0)
            strokeWidth = 260f
            maskFilter = BlurMaskFilter(120f, BlurMaskFilter.Blur.NORMAL)
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(30, 255, 255, 255)
            strokeWidth = 110f
            maskFilter = BlurMaskFilter(58f, BlurMaskFilter.Blur.NORMAL)
        }
        val crest = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(48, 255, 255, 255)
            strokeWidth = 3f
            maskFilter = BlurMaskFilter(2.2f, BlurMaskFilter.Blur.NORMAL)
        }
        for (p in curves) {
            val line = Path()
            line.moveTo(p[0] * w, p[1] * h)
            line.cubicTo(p[2] * w, p[3] * h, p[4] * w, p[5] * h, p[6] * w, p[7] * h)
            line.cubicTo(p[8] * w, p[9] * h, p[10] * w, p[11] * h, p[12] * w, p[13] * h)
            val below = Path(line)
            below.lineTo(w * 1.2f, h * 1.3f)
            below.lineTo(-w * 0.2f, h * 1.3f)
            below.close()
            var s = c.save()
            c.clipOutPath(below)
            c.drawPath(line, shade)
            c.restoreToCount(s)
            s = c.save()
            c.clipPath(below)
            c.drawPath(line, glow)
            c.restoreToCount(s)
            c.drawPath(line, crest)
        }
        return bmp
    }

    fun start() {
        running = true
        if (!animated) return
        for (a in anims) {
            if (!a.isStarted) a.start() else if (a.isPaused) a.resume()
        }
    }

    fun stop() {
        running = false
        for (a in anims) if (a.isStarted) a.pause()
    }
}
