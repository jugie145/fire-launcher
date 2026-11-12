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
        invalidate()
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
    }
}
