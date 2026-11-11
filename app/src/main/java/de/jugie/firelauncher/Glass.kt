package de.jugie.firelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Glasflaeche: leicht getoenter Koerper, weicher Verlauf, Lichtreflex oben links
 * und ein feiner heller Rand, der bei Fokus heller wird (boost 0..1).
 * Echte Hintergrundunschaerfe (RenderEffect) gibt es erst ab Android 12; Fire OS 8 ist Android 11.
 */
class GlassDrawable(private val radius: Float, private val k: Float) : Drawable() {

    var boost = 0f
        set(v) {
            field = v
            invalidateSelf()
        }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Math.max(1.5f, 2f * k)
    }
    private val rect = RectF()
    private val path = Path()

    private fun white(a: Float): Int = Color.argb((255f * Math.min(1f, Math.max(0f, a))).toInt(), 255, 255, 255)

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    /** Verlaeufe neu aufbauen, z. B. nachdem die Glas-Intensitaet geaendert wurde. */
    fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val i = Ui.intensity
        val inset = rim.strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val r = Math.min(radius, rect.height() / 2f)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        body.color = Color.argb(40, 8, 10, 18)
        fill.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(white(0.19f * i), white(0.07f * i), white(0.12f * i)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        shine.shader = RadialGradient(
            rect.left + rect.width() * 0.26f, rect.top, Math.max(1f, rect.width() * 0.8f),
            white(0.20f * i), white(0f), Shader.TileMode.CLAMP
        )
        val ri = 0.55f + 0.45f * i
        rim.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(white(0.72f * ri), white(0.16f * ri), white(0.40f * ri)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawPath(path, body)
        fill.alpha = (175 + 80 * boost).toInt()
        canvas.drawPath(path, fill)
        val save = canvas.save()
        canvas.clipPath(path)
        shine.alpha = (190 + 65 * boost).toInt()
        canvas.drawRect(rect, shine)
        canvas.restoreToCount(save)
        rim.alpha = (140 + 115 * boost).toInt()
        canvas.drawPath(path, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Selbst gezeichnete Wettersymbole. */
class WeatherIcon(ctx: Context) : View(ctx) {
    companion object {
        const val SUN = 0
        const val MOON = 1
        const val PARTLY = 2
        const val CLOUD = 3
        const val RAIN = 4
        const val SNOW = 5
        const val FOG = 6
        const val STORM = 7

        fun kindFor(code: Int, day: Boolean): Int = when (code) {
            0, 1 -> if (day) SUN else MOON
            2 -> if (day) PARTLY else CLOUD
            3 -> CLOUD
            45, 48 -> FOG
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> STORM
            else -> CLOUD
        }
    }

    var kind = CLOUD
        set(v) {
            field = v
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val cut = Path()

    private fun cloud(c: Canvas, cx: Float, cy: Float, w: Float) {
        c.drawCircle(cx - w * 0.22f, cy + w * 0.05f, w * 0.19f, fill)
        c.drawCircle(cx + w * 0.02f, cy - w * 0.08f, w * 0.26f, fill)
        c.drawCircle(cx + w * 0.27f, cy + w * 0.07f, w * 0.17f, fill)
        c.drawRoundRect(cx - w * 0.41f, cy + w * 0.03f, cx + w * 0.44f, cy + w * 0.24f, w * 0.11f, w * 0.11f, fill)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r, line)
        for (i in 0 until 8) {
            val a = Math.PI * 2 * i / 8
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * r * 1.5f, cy + sn * r * 1.5f, cx + cs * r * 2.0f, cy + sn * r * 2.0f, line)
        }
    }

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        line.strokeWidth = s * 0.055f
        when (kind) {
            SUN -> sun(c, s * 0.5f, s * 0.5f, s * 0.2f)
            MOON -> {
                path.reset()
                path.addCircle(s * 0.48f, s * 0.52f, s * 0.3f, Path.Direction.CW)
                cut.reset()
                cut.addCircle(s * 0.62f, s * 0.40f, s * 0.26f, Path.Direction.CW)
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, fill)
            }
            PARTLY -> {
                sun(c, s * 0.66f, s * 0.34f, s * 0.13f)
                cloud(c, s * 0.44f, s * 0.58f, s * 0.72f)
            }
            CLOUD -> cloud(c, s * 0.5f, s * 0.5f, s * 0.82f)
            RAIN -> {
                cloud(c, s * 0.5f, s * 0.4f, s * 0.78f)
                for (x in floatArrayOf(0.34f, 0.5f, 0.66f)) c.drawLine(s * x, s * 0.72f, s * (x - 0.05f), s * 0.88f, line)
            }
            SNOW -> {
                cloud(c, s * 0.5f, s * 0.4f, s * 0.78f)
                for (x in floatArrayOf(0.32f, 0.5f, 0.68f)) c.drawCircle(s * x, s * 0.82f, s * 0.04f, fill)
            }
            FOG -> for (y in floatArrayOf(0.34f, 0.5f, 0.66f)) c.drawLine(s * 0.18f, s * y, s * 0.82f, s * y, line)
            STORM -> {
                cloud(c, s * 0.5f, s * 0.38f, s * 0.78f)
                path.reset()
                path.moveTo(s * 0.54f, s * 0.64f)
                path.lineTo(s * 0.40f, s * 0.82f)
                path.lineTo(s * 0.50f, s * 0.82f)
                path.lineTo(s * 0.44f, s * 0.98f)
                path.lineTo(s * 0.62f, s * 0.76f)
                path.lineTo(s * 0.52f, s * 0.76f)
                path.close()
                c.drawPath(path, fill)
            }
        }
    }
}

/** Kleines WLAN-Symbol fuer die Systemleiste. */
class WifiIcon(ctx: Context) : View(ctx) {
    var connected = true
        set(v) {
            field = v
            invalidate()
        }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        val a = if (connected) 190 else 70
        line.alpha = a
        dot.alpha = a
        line.strokeWidth = s * 0.09f
        val cx = s / 2f
        val cy = s * 0.82f
        for (r in floatArrayOf(0.66f, 0.42f)) {
            oval.set(cx - s * r, cy - s * r, cx + s * r, cy + s * r)
            c.drawArc(oval, 225f, 90f, false, line)
        }
        c.drawCircle(cx, cy - s * 0.08f, s * 0.08f, dot)
    }
}
