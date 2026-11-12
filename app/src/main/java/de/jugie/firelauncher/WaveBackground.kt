package de.jugie.firelauncher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Hintergrund: das scharfe Wellenbild. Rund um die ausgewaehlte App wird dasselbe Bild in der
 * App-Farbe eingefaerbt daruebergelegt und laeuft nach aussen ins Schwarze aus. Der Farbbereich
 * gleitet zur naechsten App und wechselt dabei die Farbe; optional "atmet" er langsam.
 * Gezeichnet wird ein einziges Rechteck mit einem Shader, das kostet kaum Leistung.
 */
class WaveBackground(ctx: Context) : View(ctx) {

    private val bmp: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.bg_waves, BitmapFactory.Options().apply { inScaled = false })
    } catch (e: Throwable) {
        null
    }
    private val base = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var bmpShader: BitmapShader? = null
    private val dst = RectF()
    private val gm = Matrix()
    private val cm = ColorMatrix()
    private var k = 1f
    private var cx = -1f
    private var cy = -1f
    private var color = Ui.lastAccent
    private var level = 1f
    private var move: ValueAnimator? = null
    private var breath: ValueAnimator? = null
    private val argb = ArgbEvaluator()
    // zweiter Farbbereich (Mix): oben rechts hinter dem Vorschlag, in der Farbe des Bildes
    private val tint2 = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val cm2 = ColorMatrix()
    private var color2 = 0
    private var level2 = 0f
    private var move2: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        k = w / 1920f
        val b = bmp ?: return
        val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(w / b.width.toFloat(), h / b.height.toFloat())
        s.setLocalMatrix(m)
        bmpShader = s
        if (cx < 0f) {
            cx = w / 2f
            cy = h * 0.74f
        }
        rebuild()
    }

    private fun rebuild() {
        val s = bmpShader ?: return
        val rg = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFF000000.toInt(), 0x9E000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        gm.reset()
        gm.setScale(1050f * k, 850f * k)
        gm.postTranslate(cx, cy)
        rg.setLocalMatrix(gm)
        tint.shader = ComposeShader(s, rg, PorterDuff.Mode.DST_IN)
        val g = 2.6f * gain
        cm.set(
            floatArrayOf(
                Color.red(color) / 255f * g, 0f, 0f, 0f, 0f,
                0f, Color.green(color) / 255f * g, 0f, 0f, 0f,
                0f, 0f, Color.blue(color) / 255f * g, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        tint.colorFilter = ColorMatrixColorFilter(cm)
        rebuild2()
        invalidate()
    }

    private fun rebuild2() {
        val s = bmpShader ?: return
        val rg = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFF000000.toInt(), 0x9E000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        val m = Matrix()
        m.setScale(850f * k, 550f * k)
        m.postTranslate(1450f * k, 300f * k)
        rg.setLocalMatrix(m)
        tint2.shader = ComposeShader(s, rg, PorterDuff.Mode.DST_IN)
        val g = 2.6f
        cm2.set(
            floatArrayOf(
                Color.red(color2) / 255f * g, 0f, 0f, 0f, 0f,
                0f, Color.green(color2) / 255f * g, 0f, 0f, 0f,
                0f, 0f, Color.blue(color2) / 255f * g, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        tint2.colorFilter = ColorMatrixColorFilter(cm2)
    }

    /** Mix: Farbe des gezeigten Bildes oben rechts; 0 = ausblenden. */
    fun setSpot2(c: Int) {
        move2?.cancel()
        val fc = if (color2 == 0) c else color2
        val fl = level2
        val tl = if (c == 0) 0f else 1f
        val tc = if (c == 0) color2 else c
        if (!Ui.anims) {
            color2 = tc
            level2 = tl
            rebuild2()
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 900
        a.addUpdateListener {
            val t = it.animatedValue as Float
            color2 = argb.evaluate(t, fc, tc) as Int
            level2 = fl + (tl - fl) * t
            rebuild2()
            invalidate()
        }
        a.start()
        move2 = a
    }

    /** Farbbereich auf einen Punkt (in Pixeln) und eine Farbe setzen. */
    private var gain = 1f

    fun setSpot(x: Float, y: Float, c: Int, animate: Boolean, boost: Float = gain) {
        move?.cancel()
        if (!animate || !Ui.anims || cx < 0f || bmpShader == null) {
            cx = x
            cy = y
            color = c
            gain = boost
            rebuild()
            return
        }
        val fx = cx
        val fy = cy
        val fc = color
        val fg = gain
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 600
        a.interpolator = Ui.smooth
        a.addUpdateListener {
            val t = it.animatedValue as Float
            cx = fx + (x - fx) * t
            cy = fy + (y - fy) * t
            color = argb.evaluate(t, fc, c) as Int
            gain = fg + (boost - fg) * t
            rebuild()
        }
        a.start()
        move = a
    }

    fun start() {
        stop()
        if (!Ui.anims || !Prefs.bgAnim(context)) {
            level = 1f
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(1f, 0.74f)
        a.duration = 3500
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.addUpdateListener {
            level = it.animatedValue as Float
            invalidate()
        }
        a.start()
        breath = a
    }

    fun stop() {
        breath?.cancel()
        breath = null
    }

    override fun onDraw(c: Canvas) {
        val b = bmp
        if (b == null) {
            c.drawColor(Ui.BG)
            return
        }
        c.drawBitmap(b, null, dst, base)
        if (tint.shader != null) {
            tint.alpha = (255 * level).toInt()
            c.drawRect(dst, tint)
        }
        if (level2 > 0.01f && tint2.shader != null) {
            tint2.alpha = (255 * level * level2).toInt()
            c.drawRect(dst, tint2)
        }
    }
}
