package de.jugie.firelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uhr, deren geaenderte Ziffern beim Minutenwechsel nach oben wegrollen. */
class RollingClock(ctx: Context, size: Float, face: Typeface, private val h24: Boolean) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        typeface = face
    }
    private val fm = paint.fontMetrics
    private val lineH = fm.descent - fm.ascent
    private var cur = now()
    private var old = cur
    private var rollAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private fun now(): String = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.GERMANY).format(Date())

    private val check = object : Runnable {
        override fun run() {
            val n = now()
            if (n != cur) {
                val grew = n.length != cur.length
                old = cur
                cur = n
                rollAt = SystemClock.uptimeMillis()
                if (grew) requestLayout()
                invalidate()
            }
            if (running) handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        val n = now()
        if (n != cur) {
            // nach laengerer Pause ohne Rollen umstellen
            cur = n
            old = n
            requestLayout()
            invalidate()
        }
        handler.post(check)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(check)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Math.ceil(paint.measureText(cur).toDouble()).toInt() + 2, Math.ceil(lineH.toDouble()).toInt())
    }

    override fun onDraw(c: Canvas) {
        val base = -fm.ascent
        val t = SystemClock.uptimeMillis() - rollAt
        var x = 0f
        var busy = false
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        for (j in cur.indices) {
            val ch = cur[j].toString()
            val was = if (j < old.length && old.length == cur.length) old[j].toString() else ch
            val w = paint.measureText(ch)
            if (was != ch && Ui.anims) {
                val p = ((t - j * 40L) / 560f).coerceIn(0f, 1f)
                if (p < 1f) busy = true
                val e = Ui.smooth.getInterpolation(p)
                c.drawText(was, x, base - e * lineH, paint)
                c.drawText(ch, x, base + (1f - e) * lineH, paint)
            } else {
                c.drawText(ch, x, base, paint)
            }
            x += w
        }
        c.restore()
        if (busy) postInvalidateOnAnimation()
    }
}

/** Roter Punkt, der neben "LIVE" pulsiert. */
class LiveDot(ctx: Context) : View(ctx) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 59, 48) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 59, 48) }
    private val t0 = SystemClock.uptimeMillis()
    private val out = android.view.animation.PathInterpolator(0f, 0f, 0.58f, 1f)

    override fun onDraw(c: Canvas) {
        // Punkt in der Mitte, Platz drumherum fuer den Ring (bis 2,7-fach)
        val r = Math.min(width, height) / 5.4f
        val cx = width / 2f
        val cy = height / 2f
        if (Ui.anims) {
            val p = out.getInterpolation(((SystemClock.uptimeMillis() - t0) % 1600L) / 1600f)
            ring.alpha = (191 * (1f - p)).toInt()
            c.drawCircle(cx, cy, r * (1f + 1.7f * p), ring)
        }
        c.drawCircle(cx, cy, r, dot)
        if (Ui.anims && isShown) postInvalidateDelayed(33)
    }
}

/**
 * Klapp-Uhr aus Glaskacheln: beim Minutenwechsel klappt bei jeder geaenderten Ziffer die obere
 * Haelfte nach vorn weg und die neue untere Haelfte federnd herein (wie in der Vorschau).
 */
class FlipClock(
    ctx: Context,
    private val k: Float,
    private val tileW: Float,
    private val tileH: Float,
    textSize: Float,
    private val gap: Float,
    private val colonMargin: Float,
    private val dot: Float,
    glassRes: Int,
    private val h24: Boolean
) : View(ctx) {
    private val glass: android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeResource(resources, glassRes, android.graphics.BitmapFactory.Options().apply { inScaled = false })
    } catch (e: Throwable) {
        null
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize * k
        typeface = Ui.medium
        textAlign = Paint.Align.CENTER
    }
    private val hinge = Paint().apply { color = 0x73000000 }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val camera = android.graphics.Camera()
    private val m = android.graphics.Matrix()
    private val src = Rect()
    private val dst = RectF()
    private val downCurve = android.view.animation.PathInterpolator(0.55f, 0f, 0.85f, 0.4f)
    private val upCurve = android.view.animation.PathInterpolator(0.15f, 0.6f, 0.35f, 1.25f)
    private val pulse = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private val handler = Handler(Looper.getMainLooper())
    private var cur = digits(System.currentTimeMillis())
    private var old = cur
    private var flipAt = 0L
    private var running = false
    private val t0 = SystemClock.uptimeMillis()
    private val PAD = 16f

    private fun digits(t: Long): String = SimpleDateFormat(if (h24) "HHmm" else "hhmm", Locale.GERMANY).format(Date(t))

    private val check = object : Runnable {
        override fun run() {
            val n = digits(System.currentTimeMillis())
            if (n != cur) {
                old = cur
                cur = n
                flipAt = SystemClock.uptimeMillis()
                invalidate()
            }
            if (running) handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        // beim Start einmal von der vorigen Minute umklappen
        cur = digits(System.currentTimeMillis())
        old = digits(System.currentTimeMillis() - 60_000L)
        flipAt = SystemClock.uptimeMillis() + 300
        handler.post(check)
        invalidate()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(check)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (4 * tileW + 4 * gap + 2 * colonMargin + dot) * k
        // etwas Rand links und rechts, damit beim Umklappen nichts abgeschnitten wird
        setMeasuredDimension(Math.ceil((w + 2 * PAD * k).toDouble()).toInt(), Math.ceil((tileH * k).toDouble()).toInt())
    }

    private fun drawHalf(c: Canvas, x: Float, top: Boolean, ch: String, angle: Float) {
        val w = tileW * k
        val h = tileH * k
        val half = h / 2f
        c.save()
        if (angle != 0f) {
            camera.save()
            camera.setLocation(0f, 0f, -h * 3f / 72f)
            camera.rotateX(angle)
            camera.getMatrix(m)
            camera.restore()
            m.preTranslate(-(x + w / 2f), -half)
            m.postTranslate(x + w / 2f, half)
            c.concat(m)
        }
        if (top) c.clipRect(x, 0f, x + w, half) else c.clipRect(x, half, x + w, h)
        val g = glass
        if (g != null) {
            src.set(0, 0, g.width, g.height)
            dst.set(x, 0f, x + w, h)
            c.drawBitmap(g, src, dst, bmpPaint)
        }
        val fm = text.fontMetrics
        c.drawText(ch, x + w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(PAD * k, 0f)
        drawTiles(c)
        c.restore()
    }

    private fun drawTiles(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val w = tileW * k
        val h = tileH * k
        var busy = false
        var x = 0f
        for (j in 0 until 4) {
            if (j == 2) {
                // Doppelpunkt, pulsiert sanft
                val f = ((now - t0) % 2000L) / 2000f
                val tri = if (f < 0.5f) f * 2f else (1f - f) * 2f
                dotPaint.alpha = (255 * (1f - 0.65f * pulse.getInterpolation(tri)) * 0.85f).toInt()
                val cx = x + colonMargin * k + dot * k / 2f
                c.drawCircle(cx, h / 2f - dot * k * 1.5f, dot * k / 2f, dotPaint)
                c.drawCircle(cx, h / 2f + dot * k * 1.5f, dot * k / 2f, dotPaint)
                x += (2 * colonMargin + dot + gap) * k
            }
            val now1 = cur[j].toString()
            val was = if (old.length == cur.length) old[j].toString() else now1
            val delay = (3 - j) * 90L
            val t = now - flipAt - delay
            if (was != now1 && Ui.anims && t < 700L) {
                busy = true
                // stehend: oben schon die neue, unten noch die alte Ziffer
                drawHalf(c, x, true, now1, 0f)
                drawHalf(c, x, false, was, 0f)
                if (t < 320L) {
                    val p = downCurve.getInterpolation((Math.max(0L, t)) / 320f)
                    drawHalf(c, x, true, was, -90f * p)
                } else {
                    val q = upCurve.getInterpolation(((t - 320L) / 380f).coerceAtMost(1f))
                    drawHalf(c, x, false, now1, 90f * (1f - q))
                }
            } else {
                drawHalf(c, x, true, now1, 0f)
                drawHalf(c, x, false, now1, 0f)
            }
            c.drawRect(x + 1f, h / 2f - k, x + w - 1f, h / 2f + k, hinge)
            x += w + gap * k
        }
        if (busy) postInvalidateOnAnimation() else if (running && Ui.anims) postInvalidateDelayed(50)
    }
}
