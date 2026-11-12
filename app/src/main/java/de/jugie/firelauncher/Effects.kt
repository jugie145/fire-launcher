package de.jugie.firelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
