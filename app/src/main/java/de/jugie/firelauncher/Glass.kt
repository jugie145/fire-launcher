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
 * Liquid-Glass-Flaeche ohne Hintergrundunschaerfe (RenderEffect gibt es erst ab Android 12,
 * Fire OS 8 ist Android 11). Der Glaseindruck entsteht aus mehreren Schichten:
 *  1. halbtransparenter dunkler Koerper, durch den der Hintergrund sichtbar bleibt
 *  2. weicher Verlauf innerhalb der Flaeche
 *  3. Lichtreflex am oberen Rand
 *  4. feines inneres Highlight (Kante innen)
 *  5. feine helle Kontur mit geringer Deckkraft
 * Bei Fokus (boost 0..1) wird der Koerper durchsichtiger und heller, dazu kommt ein
 * zurueckhaltender Rahmen in der Akzentfarbe. Gezeichnet wird nur bei Aenderungen.
 */
class GlassDrawable(private val radius: Float, private val k: Float) : Drawable() {

    var boost = 0f
        set(v) {
            field = v
            invalidateSelf()
        }

    /** Farbe von Rahmen und Toenung bei Fokus. Weiss = neutral. */
    var accent = Color.WHITE

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val inRect = RectF()
    private val path = Path()
    private val inPath = Path()

    private fun white(a: Float): Int = Color.argb((255f * Math.min(1f, Math.max(0f, a))).toInt(), 255, 255, 255)

    private fun mix(c: Int, t: Float): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt()
    )

    override fun onBoundsChange(bounds: Rect) {
        rebuild()
    }

    /** Verlaeufe neu aufbauen, z. B. nachdem die Glas-Intensitaet geaendert wurde. */
    fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val i = Ui.intensity
        rim.strokeWidth = Math.max(1.2f, 1.6f * k)
        inner.strokeWidth = Math.max(1f, 1.3f * k)
        edge.strokeWidth = Math.max(2f, 3.2f * k)
        val inset = edge.strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val r = Math.min(radius, rect.height() / 2f)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        val d = Math.max(2f, 3f * k)
        inRect.set(rect.left + d, rect.top + d, rect.right - d, rect.bottom - d)
        val ir = Math.max(0f, r - d)
        inPath.reset()
        inPath.addRoundRect(inRect, ir, ir, Path.Direction.CW)

        // 2. weicher Verlauf: oben heller, Mitte klar, unten ein Hauch Licht
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(white(0.13f * i), white(0.03f * i), white(0.07f * i)),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        // 3. Lichtreflex am oberen Rand, leicht nach links versetzt
        sheen.shader = RadialGradient(
            rect.left + rect.width() * 0.38f, rect.top - rect.height() * 0.28f,
            Math.max(1f, Math.max(rect.width() * 0.62f, rect.height() * 0.9f)),
            intArrayOf(white(0.26f * i), white(0.07f * i), white(0f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        // 4. inneres Highlight: oben sichtbar, laeuft zur Mitte aus, unten ganz leicht
        inner.shader = LinearGradient(
            0f, inRect.top, 0f, inRect.bottom,
            intArrayOf(white(0.22f * i), white(0f), white(0f), white(0.07f * i)),
            floatArrayOf(0f, 0.34f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        // 5. feine Kontur: oben am hellsten, an den Seiten zurueckhaltend
        val ri = 0.6f + 0.4f * i
        rim.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(white(0.50f * ri), white(0.13f * ri), white(0.24f * ri)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        // 1. dunkler Koerper: normal dichter, ausgewaehlt durchsichtiger
        body.color = Color.argb((104 - 50 * boost).toInt(), 9, 12, 22)
        canvas.drawPath(path, body)
        fill.alpha = (185 + 70 * boost).toInt()
        canvas.drawPath(path, fill)
        if (accent != Color.WHITE && boost > 0.01f) {
            tint.color = accent
            tint.alpha = (66 * boost).toInt()
            canvas.drawPath(path, tint)
        }
        val save = canvas.save()
        canvas.clipPath(path)
        sheen.alpha = (165 + 90 * boost).toInt()
        canvas.drawRect(rect, sheen)
        canvas.restoreToCount(save)
        inner.alpha = (170 + 85 * boost).toInt()
        canvas.drawPath(inPath, inner)
        rim.alpha = (150 + 90 * boost).toInt()
        canvas.drawPath(path, rim)
        if (boost > 0.01f) {
            // zurueckhaltender Fokusrahmen: Akzentfarbe mit Weiss aufgehellt, nicht voll deckend
            edge.color = if (accent == Color.WHITE) Color.WHITE else mix(accent, 0.22f)
            edge.alpha = ((if (accent == Color.WHITE) 120 else 240) * boost).toInt()
            canvas.drawPath(path, edge)
        }
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

    /** Lebendiges Symbol: Sonnenstrahlen drehen sich, Wolken ziehen, Regen faellt. */
    var live = false
        set(v) {
            field = v
            invalidate()
        }
    private val t0 = android.os.SystemClock.uptimeMillis()
    private var spin = 0f
    private var drift = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val cut = Path()

    private fun cloud(c: Canvas, cx0: Float, cy: Float, w: Float) {
        val cx = cx0 + drift
        c.drawCircle(cx - w * 0.22f, cy + w * 0.05f, w * 0.19f, fill)
        c.drawCircle(cx + w * 0.02f, cy - w * 0.08f, w * 0.26f, fill)
        c.drawCircle(cx + w * 0.27f, cy + w * 0.07f, w * 0.17f, fill)
        c.drawRoundRect(cx - w * 0.41f, cy + w * 0.03f, cx + w * 0.44f, cy + w * 0.24f, w * 0.11f, w * 0.11f, fill)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r, line)
        for (i in 0 until 8) {
            val a = Math.PI * 2 * i / 8 + Math.toRadians(spin.toDouble())
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * r * 1.5f, cy + sn * r * 1.5f, cx + cs * r * 2.0f, cy + sn * r * 2.0f, line)
        }
    }

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        line.strokeWidth = s * 0.055f
        val animate = live && Ui.anims
        val t = android.os.SystemClock.uptimeMillis() - t0
        if (animate) {
            spin = (t % 24000L) * 360f / 24000f
            // Wolke zieht in 7 s sanft hin und her (wie in der Vorschau: -0,8 bis +1,2 von 24)
            val e = ((1 - Math.cos(2 * Math.PI * (t % 7000L) / 7000.0)) / 2).toFloat()
            drift = s * (-0.8f + 2f * e) / 24f
        } else {
            spin = 0f
            drift = 0f
        }
        line.alpha = 255
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
                val xs = floatArrayOf(0.34f, 0.5f, 0.66f)
                for (i in xs.indices) {
                    val x = xs[i]
                    if (animate) {
                        // Tropfen fallen nacheinander (1,1 s, versetzt) und verblassen
                        val f = ((t + i * 367L) % 1100L) / 1100f
                        val dy = s * (-1.5f + 5f * f) / 24f
                        line.alpha = (255 * (if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f)).toInt()
                        c.drawLine(s * x, s * 0.72f + dy, s * (x - 0.05f), s * 0.88f + dy, line)
                    } else {
                        c.drawLine(s * x, s * 0.72f, s * (x - 0.05f), s * 0.88f, line)
                    }
                }
                line.alpha = 255
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
        if (animate && kind != MOON && kind != FOG) postInvalidateDelayed(33)
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

/** Standort-Nadel. */
class PinIcon(ctx: Context) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 200
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        line.strokeWidth = s * 0.08f
        oval.set(s * 0.2f, s * 0.08f, s * 0.8f, s * 0.68f)
        path.reset()
        path.addArc(oval, 150f, 240f)
        path.lineTo(s * 0.5f, s * 0.94f)
        path.close()
        c.drawPath(path, line)
        c.drawCircle(s * 0.5f, s * 0.38f, s * 0.09f, line)
    }
}

/** Zahnrad fuer die Fussleiste. */
class GearIcon(ctx: Context) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 200
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        val cx = s / 2f
        val cy = s / 2f
        line.strokeWidth = s * 0.13f
        c.drawCircle(cx, cy, s * 0.24f, line)
        line.strokeWidth = s * 0.15f
        for (i in 0 until 8) {
            val a = Math.PI * 2 * i / 8
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * s * 0.35f, cy + sn * s * 0.35f, cx + cs * s * 0.41f, cy + sn * s * 0.41f, line)
        }
    }
}

/** Runder Wiedergabe-Knopf im Spotlight. */
class PlayIcon(ctx: Context) : View(ctx) {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 255, 255, 255) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
    }
    private val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        ring.strokeWidth = Math.max(1.5f, s * 0.02f)
        c.drawCircle(s / 2f, s / 2f, s * 0.48f, disc)
        c.drawCircle(s / 2f, s / 2f, s * 0.48f, ring)
        path.reset()
        path.moveTo(s * 0.41f, s * 0.32f)
        path.lineTo(s * 0.68f, s * 0.5f)
        path.lineTo(s * 0.41f, s * 0.68f)
        path.close()
        c.drawPath(path, tri)
    }
}
