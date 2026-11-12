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

/**
 * Farbige, lebendige Wettersymbole, gezeichnet in einem 24er-Raster wie in der Vorschau:
 * Sonnenstrahlen drehen sich und die Sonne leuchtet, Wolken ziehen gegeneinander,
 * Regentropfen fallen, Schneeflocken taumeln, nachts funkeln Sterne.
 */
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

        private const val SUN_C = 0xFFFFC94D.toInt()
        private const val RAIN_C = 0xFF7CC4FF.toInt()
        private const val BACK_C = 0xFFAEB8C8.toInt()
        private const val MOON_C = 0xFFE8ECF5.toInt()

        /** Wolke aus der Vorschau (dieselben Boegen), im 24er-Raster */
        private val CLOUD_PATH: Path by lazy {
            val p = Path()
            p.addCircle(16.75f, 15.02f, 3.5f, Path.Direction.CW)
            p.addCircle(12.25f, 13.10f, 5f, Path.Direction.CW)
            p.addCircle(7.683f, 15.476f, 3.1f, Path.Direction.CW)
            p.addRect(7f, 13f, 16.75f, 18.5f, Path.Direction.CW)
            val u = Path()
            u.addRect(0f, 0f, 24f, 18.5f, Path.Direction.CW)
            p.op(u, Path.Op.INTERSECT)
            p
        }

        /** Mondsichel: grosser Kreis ohne den versetzten zweiten */
        private val MOON_PATH: Path by lazy {
            val p = Path()
            p.addCircle(15.155f, 11.99f, 7.5f, Path.Direction.CW)
            val c = Path()
            c.addCircle(17.5f, 11.3f, 7.09f, Path.Direction.CW)
            p.op(c, Path.Op.DIFFERENCE)
            p
        }
    }

    var kind = CLOUD
        set(v) {
            field = v
            invalidate()
        }

    var live = false
        set(v) {
            field = v
            invalidate()
        }

    private val t0 = android.os.SystemClock.uptimeMillis()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val inOut = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)

    /** Wert zwischen a und b, hin und zurueck in [period] ms (wie CSS 0%/100% = a, 50% = b, ease-in-out) */
    private fun swing(t: Long, period: Long, a: Float, b: Float, delay: Long = 0): Float {
        val f = (((t - delay) % period + period) % period) / period.toFloat()
        val half = if (f < 0.5f) f * 2f else (1f - f) * 2f
        return a + (b - a) * inOut.getInterpolation(half)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, t: Long, anim: Boolean) {
        // Leuchten
        val g = if (anim) swing(t, 3200, 0f, 1f) else 0.5f
        fill.color = SUN_C
        fill.alpha = (255 * (0.18f + 0.24f * g)).toInt()
        c.drawCircle(cx, cy, r * 2f * (0.88f + 0.24f * g), fill)
        // Strahlen
        line.color = SUN_C
        line.alpha = 255
        line.strokeWidth = 1.2f
        val rot = if (anim) (t % 24000L) * 360f / 24000f else 0f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45 + rot).toDouble())
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * r * 1.35f, cy + sn * r * 1.35f, cx + cs * r * 1.8f, cy + sn * r * 1.8f, line)
        }
        fill.alpha = 255
        c.drawCircle(cx, cy, r, fill)
    }

    private fun clouds(c: Canvas, t: Long, anim: Boolean, dy: Float, back: Boolean) {
        if (back) {
            c.save()
            c.translate(if (anim) swing(t, 9000, 1f, -1.4f) else 0f, 0f)
            c.translate(9f, -3.2f + dy)
            c.scale(0.62f, 0.62f)
            fill.color = BACK_C
            c.drawPath(CLOUD_PATH, fill)
            c.restore()
        }
        c.save()
        c.translate(if (anim) swing(t, 7000, -0.8f, 1.2f) else 0f, dy)
        fill.color = Color.WHITE
        c.drawPath(CLOUD_PATH, fill)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        if (s <= 0f) return
        val anim = live && Ui.anims
        val t = android.os.SystemClock.uptimeMillis() - t0
        c.save()
        c.translate((width - s) / 2f, (height - s) / 2f)
        c.scale(s / 24f, s / 24f)
        fill.alpha = 255
        when (kind) {
            SUN -> sun(c, 12f, 12f, 4f, t, anim)
            PARTLY -> {
                sun(c, 15.5f, 8f, 2.7f, t, anim)
                clouds(c, t, anim, 1f, false)
            }
            CLOUD, FOG -> clouds(c, t, anim, 0f, true)
            RAIN, STORM -> {
                clouds(c, t, anim, -3.5f, true)
                line.color = RAIN_C
                line.strokeWidth = 1.4f
                val xs = floatArrayOf(9.3f, 12.8f, 16.3f)
                for (i in xs.indices) {
                    var dy = 0f
                    var a = 1f
                    if (anim) {
                        val f = ((t - i * 370L) % 1100L + 1100L) % 1100L / 1100f
                        dy = -1.5f + 5f * f
                        a = if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f
                    }
                    line.alpha = (255 * a).toInt()
                    c.drawLine(xs[i], 18.6f + dy, xs[i] - 0.6f, 20.8f + dy, line)
                }
            }
            SNOW -> {
                clouds(c, t, anim, -3.5f, true)
                fill.color = Color.WHITE
                val xs = floatArrayOf(9.3f, 12.8f, 16.3f)
                for (i in xs.indices) {
                    var dx = 0f
                    var dy = 0f
                    var a = 1f
                    if (anim) {
                        val f = ((t - i * 800L) % 2400L + 2400L) % 2400L / 2400f
                        val e = inOut.getInterpolation(f)
                        if (e < 0.6f) {
                            dx = 0.8f * e / 0.6f
                            dy = -1.5f + 3f * e / 0.6f
                        } else {
                            dx = 0.8f - 1.2f * (e - 0.6f) / 0.4f
                            dy = 1.5f + 2.5f * (e - 0.6f) / 0.4f
                        }
                        a = if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f
                    }
                    fill.alpha = (255 * a).toInt()
                    c.drawCircle(xs[i] + dx, 19.6f + dy, 0.95f, fill)
                }
                fill.alpha = 255
            }
            MOON -> {
                fill.color = Color.WHITE
                val stars = arrayOf(floatArrayOf(5f, 5f, 0.7f), floatArrayOf(8.5f, 2.5f, 0.5f), floatArrayOf(3f, 10f, 0.5f))
                for (i in stars.indices) {
                    val a = if (anim) swing(t, 2000L + i * 700L, 0.25f, 1f, i * 600L) else 0.8f
                    fill.alpha = (255 * a).toInt()
                    c.drawCircle(stars[i][0], stars[i][1], stars[i][2], fill)
                }
                fill.alpha = 255
                fill.color = MOON_C
                c.drawPath(MOON_PATH, fill)
            }
        }
        c.restore()
        if (anim) postInvalidateDelayed(33)
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
