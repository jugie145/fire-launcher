package de.jugie.firelauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Ein HSV-Ereignis fuer die grosse Animation im Bildschirmschoner. */
class HsvEvent(
    val word: String, val left: String, val right: String,
    val s1: String = "", val s2: String = "", val score: Boolean = true,
    val sub: String = "", val good: Boolean = false, val bad: Boolean = false
)

/**
 * "Fan-Choreo": blau-weiss-schwarze Fahnenbahnen wehen herein, Wappen ploppt auf, ein Banner rollt sich aus,
 * unten wehen Faehnchen, bei Toren und Siegen faellt Konfetti. Bei Gegentor und Niederlage alles gedaempft.
 * Gezeichnet im 1920 x 1080-Raster, dauert gut 10 s, danach onDone.
 */
class FanChoreoView(ctx: Context, private val ev: HsvEvent) : View(ctx) {
    var onDone: (() -> Unit)? = null
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val crest: Bitmap? = try { BitmapFactory.decodeResource(resources, R.drawable.logo_hsv) } catch (e: Throwable) { null }
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private val condPlain = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val rnd = java.util.Random(7)
    private val bandColors = intArrayOf(0xFF1A5EC9.toInt(), 0xFFF2F4F8.toInt(), 0xFF0B0B0D.toInt())
    private val dull = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0.35f)
        val b = ColorMatrix(floatArrayOf(0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        postConcat(b)
    })
    private class Flag(val x: Float, val h: Float, val period: Float, val delay: Float, val color: Int)
    private val flags = List(16) { i -> Flag(40f + i * 118 + rnd.nextInt(29) - 14, 110f + rnd.nextInt(56), 900f + rnd.nextInt(500), rnd.nextInt(1000).toFloat(), bandColors[i % 3]) }
    private class Bit(val x: Float, val sw: Float, val rot: Float, val dur: Float, val delay: Float, val color: Int, val round: Boolean)
    private val bits = List(if (ev.good) 80 else 0) {
        Bit(rnd.nextInt(1920).toFloat(), rnd.nextInt(160) - 80f, rnd.nextInt(360).toFloat(), 3600f + rnd.nextInt(1400), 1150f + rnd.nextInt(900),
            intArrayOf(0xFF1E63D6.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF4D8DFF.toInt())[rnd.nextInt(4)], rnd.nextInt(3) == 0)
    }
    private val pop = PathInterpolator(0.2f, 1.3f, 0.35f, 1f)
    private val bandIn = PathInterpolator(0.3f, 0.9f, 0.3f, 1f)
    private val unfurl = PathInterpolator(0.6f, 0f, 0.2f, 1f)
    private val rise = PathInterpolator(0.2f, 1.1f, 0.35f, 1f)
    private val ease = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private val fall = PathInterpolator(0.3f, 0.1f, 0.6f, 1f)
    private val crestPop = PathInterpolator(0.2f, 1f, 0.35f, 1f)

    companion object {
        const val LENGTH = 10500L
    }

    fun play() {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, LENGTH.toFloat())
        a.duration = LENGTH
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { t = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { onDone?.invoke() }
        })
        a.start()
        anim = a
    }

    fun cancel() {
        anim?.removeAllListeners()
        anim?.cancel()
    }

    private fun prog(start: Float, dur: Float): Float = min(1f, max(0f, (t - start) / dur))

    override fun onDraw(c: Canvas) {
        val k = width / 1920f
        val all = 1f - prog(9600f, 900f)
        if (all <= 0f) return
        c.save()
        c.scale(k, k)
        val layerAlpha = (255 * all).toInt()
        c.saveLayerAlpha(0f, 0f, 1920f, 1080f, layerAlpha)

        // Hintergrund
        p.shader = null
        p.colorFilter = null
        p.color = 0xFF03060E.toInt()
        p.alpha = (255 * prog(0f, 500f)).toInt()
        c.drawRect(0f, 0f, 1920f, 1080f, p)

        // Fahnenbahnen
        p.colorFilter = if (ev.bad) dull else null
        val period = if (ev.good) 1100f else 2400f
        val skew = tan(-14.0 * PI / 180.0).toFloat()
        for (i in 0 until 10) {
            val inP = bandIn.getInterpolation(prog(i * 60f, 900f))
            if (t < i * 60f) continue
            val dx = -2700f * (1f - inP)
            val ph = (t - i * 180f) / period
            val wy = if (t > i * 180f) -30f * (0.5f - 0.5f * cos(2 * PI * ph).toFloat()) else 0f
            c.save()
            c.translate(-260f + i * 250f + 126f + dx, -300f + 850f + wy)
            c.skew(skew, 0f)
            p.color = bandColors[i % 3]
            p.alpha = 255
            c.drawRect(-126f, -850f, 126f, 850f, p)
            c.restore()
        }
        p.colorFilter = null

        // Abdunkeln zur Mitte hin weniger
        val vg = prog(600f, 800f)
        if (vg > 0f) {
            val sh = RadialGradient(0f, 0f, 900f, intArrayOf(0x2603060E, 0xBF03060E.toInt()), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setScale(1f, 520f / 900f)
            m.postTranslate(960f, 540f)
            sh.setLocalMatrix(m)
            p.shader = sh
            p.alpha = (255 * vg).toInt()
            c.drawRect(0f, 0f, 1920f, 1080f, p)
            p.shader = null
        }

        // Faehnchen unten
        if (!ev.bad && t > 1500f) {
            val ry = 340f * (1f - rise.getInterpolation(prog(1500f, 900f)))
            for (f in flags) {
                val ph = ((t + f.delay) / f.period)
                // hin und her (alternate) mit weichem Umkehren
                val sw = -9f + 18f * ease.getInterpolation(triangle(ph))
                c.save()
                c.translate(f.x + 2f, 1090f + ry)
                c.rotate(sw)
                p.color = 0xFFC9CED8.toInt()
                c.drawRect(-2f, -f.h, 2f, 0f, p)
                val path = Path()
                path.moveTo(2f, -f.h + 4f)
                path.lineTo(92f, -f.h + 32f)
                path.lineTo(2f, -f.h + 60f)
                path.close()
                p.color = f.color
                c.drawPath(path, p)
                c.restore()
            }
        }

        // Wappen im weissen Kreis
        drawCrest(c)

        // Banner mit dem Wort
        val uf = unfurl.getInterpolation(prog(1100f, 650f))
        if (uf > 0f) {
            c.save()
            c.scale(uf, 1f, 960f, 605f)
            p.color = 0xFF0B0B0D.toInt()
            c.drawRect(-20f, 488f, 1940f, 722f, p)
            p.color = Color.WHITE
            c.drawRect(-20f, 494f, 1940f, 716f, p)
            p.shader = LinearGradient(0f, 500f, 0f, 710f, 0xFF2368D8.toInt(), 0xFF134AA6.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(-20f, 500f, 1940f, 710f, p)
            p.shader = null
            c.restore()
            val tp = prog(1550f, 500f)
            if (tp > 0f) {
                val size = when {
                    ev.word.length <= 8 -> 170f
                    ev.word.length <= 13 -> 128f
                    else -> 104f
                }
                txt.typeface = cond
                txt.textSize = size
                txt.letterSpacing = 0.04f
                val y0 = 605f + size * 0.36f + 30f * (1f - pop.getInterpolation(tp))
                val beat = if (ev.good && t > 2100f) 1f + 0.06f * (0.5f - 0.5f * cos(2 * PI * (t - 2100f) / 700f).toFloat()) else 1f
                c.save()
                c.scale(beat, beat, 960f, 605f)
                txt.color = 0xFF0B0B0D.toInt()
                txt.alpha = (255 * tp).toInt()
                c.drawText(ev.word, 960f, y0 + 6f, txt)
                txt.color = Color.WHITE
                txt.alpha = (255 * tp).toInt()
                c.drawText(ev.word, 960f, y0, txt)
                c.restore()
            }
        }

        // Spielstand und Zeile darunter
        val sp = prog(2000f, 600f)
        if (sp > 0f) {
            val dy = 30f * (1f - sp)
            val a = (255 * sp).toInt()
            txt.typeface = condPlain
            txt.textSize = 64f
            txt.letterSpacing = 0.03f
            val mid = if (ev.score) "  " + ev.s1 + " : " + ev.s2 + "  " else "  "
            val lw = txt.measureText(ev.left)
            val mw = txt.measureText(mid)
            val rw = txt.measureText(ev.right)
            val total = lw + mw + rw
            val top = 740f + dy
            val box = RectF(960f - total / 2 - 40f, top, 960f + total / 2 + 40f, top + 90f)
            p.color = 0xFF0B0B0D.toInt()
            p.alpha = a
            c.drawRoundRect(box, 16f, 16f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.WHITE
            p.alpha = a
            c.drawRoundRect(box, 16f, 16f, p)
            p.style = Paint.Style.FILL
            val base = top + 68f
            txt.textAlign = Paint.Align.LEFT
            var x = 960f - total / 2
            txt.color = Color.WHITE; txt.alpha = a
            c.drawText(ev.left, x, base, txt); x += lw
            txt.color = 0xFF6FA4FF.toInt(); txt.alpha = a
            c.drawText(mid, x, base, txt); x += mw
            txt.color = Color.WHITE; txt.alpha = a
            c.drawText(ev.right, x, base, txt)
            txt.textAlign = Paint.Align.CENTER
            if (ev.sub.isNotEmpty()) {
                txt.typeface = Ui.medium
                txt.textSize = 28f
                txt.letterSpacing = 0.05f
                val sw = txt.measureText(ev.sub)
                val pill = RectF(960f - sw / 2 - 22f, top + 106f, 960f + sw / 2 + 22f, top + 106f + 48f)
                p.color = 0xCC0B0B0D.toInt()
                p.alpha = (204 * sp).toInt()
                c.drawRoundRect(pill, 24f, 24f, p)
                txt.color = 0xEBFFFFFF.toInt()
                txt.alpha = (235 * sp).toInt()
                c.drawText(ev.sub, 960f, top + 106f + 34f, txt)
            }
        }

        // Konfetti
        for (b in bits) {
            if (t < b.delay) continue
            val f = min(1f, (t - b.delay) / b.dur)
            val fy = fall.getInterpolation(f)
            val alpha = if (f < 0.08f) f / 0.08f else 1f - 0.1f * (f - 0.08f) / 0.92f
            c.save()
            c.translate(b.x + b.sw * fy, -40f + 1240f * fy)
            c.rotate(b.rot + 720f * fy)
            p.color = b.color
            p.alpha = (255 * alpha).toInt()
            if (b.round) c.drawCircle(0f, 0f, 4f, p) else c.drawRect(-5.5f, -2.5f, 5.5f, 2.5f, p)
            c.restore()
        }

        c.restore()
        c.restore()
    }

    private fun triangle(x: Float): Float {
        val f = x - Math.floor(x.toDouble()).toFloat()
        // 0..1..0 je Periode, damit es hin und zurueck schwingt
        return if (f < 0.5f) f * 2f else 2f - f * 2f
    }

    private fun drawCrest(c: Canvas) {
        fun popAt(start: Float): Pair<Float, Float>? {
            val f = prog(start, 800f)
            if (f <= 0f) return null
            // 0 -> 70 %: klein und gedreht zu leicht zu gross, dann einpendeln
            val s: Float
            val r: Float
            if (f < 0.7f) {
                val e = crestPop.getInterpolation(f / 0.7f)
                s = 0.2f + 0.9f * e
                r = -25f + 30f * e
            } else {
                val e = (f - 0.7f) / 0.3f
                s = 1.1f - 0.1f * e
                r = 5f - 5f * e
            }
            return s to r
        }
        val a = popAt(700f) ?: return
        val alpha = min(1f, prog(700f, 560f))
        c.save()
        c.translate(960f, 300f)
        c.rotate(a.second)
        c.scale(a.first, a.first)
        p.color = 0x99000000.toInt()
        p.alpha = (153 * alpha).toInt()
        c.drawCircle(0f, 20f, 170f, p)
        p.color = 0xFF0B0B0D.toInt(); p.alpha = (255 * alpha).toInt()
        c.drawCircle(0f, 0f, 166f, p)
        p.color = 0xFF1A5EC9.toInt(); p.alpha = (255 * alpha).toInt()
        c.drawCircle(0f, 0f, 160f, p)
        p.color = Color.WHITE; p.alpha = (255 * alpha).toInt()
        c.drawCircle(0f, 0f, 150f, p)
        c.restore()
        val b = popAt(800f) ?: return
        val cb = crest ?: return
        c.save()
        c.translate(960f, 300f)
        c.rotate(b.second)
        c.scale(b.first, b.first)
        p.alpha = (255 * min(1f, prog(800f, 560f))).toInt()
        c.drawBitmap(cb, null, RectF(-115f, -86f, 115f, 86f), p)
        c.restore()
        p.alpha = 255
    }
}

/**
 * Beobachtet waehrend des Bildschirmschoners das HSV-Spiel und zeigt bei Spieltag, Anpfiff, Toren,
 * Gegentoren und Spielende die Fan-Choreo ueber den ganzen Bildschirm.
 */
class HsvSaverWatch(private val host: FrameLayout) {
    private val ctx = host.context
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var games: List<Hsv.Match> = emptyList()
    private var sawBefore = false
    private var baseHsv = -1
    private var baseOpp = -1
    private var lastDayShow = 0L
    private var current: FanChoreoView? = null
    private val queue = ArrayList<HsvEvent>()

    companion object {
        /** Spielende nur einmal pro Spiel zeigen (auch ueber mehrere Schoner hinweg). */
        @Volatile var endShownFor = 0L
    }

    fun start() {
        if (running) return
        if (Theme.gta || !Prefs.hsvNote(ctx)) return
        running = true
        sawBefore = false
        baseHsv = -1
        baseOpp = -1
        Content.loadHsv { g ->
            if (!running) return@loadHsv
            games = g
            handler.postDelayed(check, 4000)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        current?.let {
            it.cancel()
            host.removeView(it)
        }
        current = null
    }

    private val check: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val info = Hsv.info(games, now)
            val m = info?.match
            if (info == null || m == null) {
                handler.postDelayed(this, 10 * 60_000L)
                return
            }
            if (!info.live) {
                sawBefore = true
                // Spieltag: beim Start des Schoners und dann hoechstens stuendlich
                val today = info.title.contains("HEUTE")
                if (today && now - lastDayShow > 60 * 60_000L) {
                    lastDayShow = now
                    val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(m.kick))
                    val left = Math.max(1L, (m.kick - now) / 60000L)
                    val wait = if (left >= 60) "noch ${left / 60} Std. ${left % 60} Min." else "noch $left Min."
                    show(HsvEvent("HSV SPIELT HEUTE", "gegen " + m.opp, "", score = false, sub = "Anstoß $time Uhr · $wait"))
                }
                handler.postDelayed(this, 60_000L)
                return
            }
            Content.loadLive(m) { l ->
                if (!running || l == null) return@loadLive
                if (baseHsv < 0) {
                    if (sawBefore) show(HsvEvent("ANPFIFF!", "HSV", m.opp, "0", "0", sub = "Das Spiel läuft – der Spielstand kommt live"))
                    baseHsv = l.hsv
                    baseOpp = l.opp
                } else {
                    if (l.hsv > baseHsv) show(HsvEvent("TOOOR!", "HSV", m.opp, "${l.hsv}", "${l.opp}", sub = l.scorerLine(), good = true))
                    else if (l.opp > baseOpp) show(HsvEvent("GEGENTOR", "HSV", m.opp, "${l.hsv}", "${l.opp}", sub = l.scorerLine(), bad = true))
                    baseHsv = l.hsv
                    baseOpp = l.opp
                }
                if (l.finished && endShownFor != m.id) {
                    endShownFor = m.id
                    val names = l.hsvScorers.distinct()
                    when {
                        l.hsv > l.opp -> show(HsvEvent("SIEG!", "HSV", m.opp, "${l.hsv}", "${l.opp}",
                            sub = if (names.isEmpty()) "Endstand" else "Endstand · Tore: " + names.joinToString(", "), good = true))
                        l.hsv == l.opp -> show(HsvEvent("UNENTSCHIEDEN", "HSV", m.opp, "${l.hsv}", "${l.opp}", sub = "Endstand"))
                        else -> show(HsvEvent("NIEDERLAGE", "HSV", m.opp, "${l.hsv}", "${l.opp}", sub = "Endstand", bad = true))
                    }
                }
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    private fun show(e: HsvEvent) {
        if (current != null) {
            queue.add(e)
            return
        }
        val v = FanChoreoView(ctx, e)
        v.onDone = {
            host.removeView(v)
            if (current === v) current = null
            if (running && queue.isNotEmpty()) show(queue.removeAt(0))
        }
        host.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        current = v
        v.play()
    }
}
