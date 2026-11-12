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
        const val PARTLY_NIGHT = 8
        const val DRIZZLE = 9
        const val SHOWERS = 10
        const val SLEET = 11
        const val WIND = 12

        fun kindFor(code: Int, day: Boolean, gust: Double = Weather.gust): Int {
            val k = when (code) {
                0, 1 -> if (day) SUN else MOON
                2 -> if (day) PARTLY else PARTLY_NIGHT
                3 -> CLOUD
                45, 48 -> FOG
                51, 53, 55 -> DRIZZLE
                56, 57, 66, 67 -> SLEET
                61, 63, 65 -> RAIN
                80, 81, 82 -> if (day) SHOWERS else RAIN
                71, 73, 75, 77, 85, 86 -> SNOW
                95, 96, 99 -> STORM
                else -> CLOUD
            }
            // Sturmboeen ab 75 km/h (Gewitter bleibt Gewitter)
            return if (gust >= 75 && k != STORM) WIND else k
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

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, t: Long, anim: Boolean) {
        // weiches Leuchten: Verlauf von warmem Gelb nach durchsichtig, atmet langsam
        val g = if (anim) swing(t, 3200, 0f, 1f) else 0.5f
        val gr = r * (2.3f + 0.35f * g)
        glowPaint.shader = android.graphics.RadialGradient(
            cx, cy, gr,
            intArrayOf(0x8CFFC94D.toInt(), 0x40FFB347, 0x14FFA040, 0x00FFA040),
            floatArrayOf(0.35f, 0.55f, 0.78f, 1f), android.graphics.Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (255 * (0.75f + 0.25f * g)).toInt()
        c.drawCircle(cx, cy, gr, glowPaint)
        // Strahlen mit runden Enden
        line.color = SUN_C
        line.alpha = 235
        line.strokeWidth = 1.3f
        line.strokeCap = Paint.Cap.ROUND
        val rot = if (anim) (t % 24000L) * 360f / 24000f else 0f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45 + rot).toDouble())
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(cx + cs * r * 1.4f, cy + sn * r * 1.4f, cx + cs * r * 1.85f, cy + sn * r * 1.85f, line)
        }
        // Sonne mit hellem Kern
        discPaint.shader = android.graphics.RadialGradient(
            cx - r * 0.3f, cy - r * 0.3f, r * 1.3f,
            intArrayOf(0xFFFFF1B8.toInt(), SUN_C, 0xFFFFA93A.toInt()), floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, discPaint)
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

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathTmp = Path()

    /** Tropfen fallen: Positionen, Laenge, Dauer, Versatz */
    private fun fall(c: Canvas, t: Long, anim: Boolean, xs: FloatArray, y0: Float, len: Float, slant: Float,
                     period: Long, step: Long, travel: Float, width: Float, color: Int, maxA: Float = 1f) {
        line.color = color
        line.strokeWidth = width
        for (i in xs.indices) {
            var dy = 0f
            var a = 1f
            if (anim) {
                val f = ((t - i * step) % period + period) % period / period.toFloat()
                dy = -1.5f + travel * f
                a = if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f
            }
            line.alpha = (255 * a * maxA).toInt()
            c.drawLine(xs[i], y0 + dy, xs[i] - slant, y0 + len + dy, line)
        }
        line.alpha = 255
    }

    /** Mond: Sichel mit sanftem Schimmer, wiegt sich leicht (ohne Sterne) */
    private fun moon(c: Canvas, t: Long, anim: Boolean, cx: Float, cy: Float, s: Float) {
        val g = if (anim) swing(t, 4200, 0f, 1f) else 0.5f
        val gr = 9.5f * s * (1f + 0.08f * g)
        glowPaint.shader = android.graphics.RadialGradient(
            cx - 1.2f * s, cy, gr, intArrayOf(0x4DCDDCFF, 0x1FBECDFF, 0x00BECDFF), floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        glowPaint.alpha = (255 * (0.7f + 0.3f * g)).toInt()
        c.drawCircle(cx - 1.2f * s, cy, gr, glowPaint)
        c.save()
        c.translate(cx, cy)
        if (anim) c.rotate(Math.toDegrees(swing(t, 6000, -0.07f, 0.07f).toDouble()).toFloat())
        c.scale(s, s)
        c.translate(-15.155f, -11.99f)
        shadePaint.shader = android.graphics.LinearGradient(8f, 5f, 18f, 19f, intArrayOf(Color.WHITE, MOON_C, 0xFFC3CEE2.toInt()),
            floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawPath(MOON_PATH, shadePaint)
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
            CLOUD -> clouds(c, t, anim, 0f, true)
            PARTLY_NIGHT -> {
                moon(c, t, anim, 15.6f, 7.6f, 0.5f)
                clouds(c, t, anim, 1f, false)
            }
            FOG -> {
                clouds(c, t, anim, -2.5f, true)
                line.color = 0xE6DCE4F0.toInt()
                line.strokeWidth = 1.3f
                val rows = arrayOf(floatArrayOf(6f, 18f, 18.6f), floatArrayOf(4.5f, 15.5f, 20.9f), floatArrayOf(9f, 19.5f, 23f))
                for (i in rows.indices) {
                    val o = if (anim) swing(t, 4200L + i * 900L, -1.4f, 1.4f, i * 700L) * (if (i % 2 == 1) -1f else 1f) else 0f
                    line.alpha = (255 * (0.9f - i * 0.2f)).toInt()
                    c.drawLine(rows[i][0] + o, rows[i][2], rows[i][1] + o, rows[i][2], line)
                }
                line.alpha = 255
            }
            RAIN -> {
                clouds(c, t, anim, -3.5f, true)
                fall(c, t, anim, floatArrayOf(9.3f, 12.8f, 16.3f), 18.6f, 2.2f, 0.6f, 1100L, 370L, 5f, 1.4f, RAIN_C)
            }
            DRIZZLE -> {
                clouds(c, t, anim, -3.5f, true)
                fall(c, t, anim, floatArrayOf(8.6f, 11.4f, 14.2f, 17f), 19.9f, 0.9f, 0.2f, 1700L, 420L, 3.6f, 1f, RAIN_C, 0.85f)
            }
            SHOWERS -> {
                sun(c, 15.8f, 6.4f, 2.5f, t, anim)
                clouds(c, t, anim, -2.2f, false)
                fall(c, t, anim, floatArrayOf(10.3f, 13.8f), 19.6f, 2.2f, 0.6f, 1100L, 550L, 5f, 1.4f, RAIN_C)
            }
            SLEET -> {
                clouds(c, t, anim, -3.5f, true)
                val xs = floatArrayOf(9.3f, 12.8f, 16.3f)
                for (i in xs.indices) {
                    var dy = 0f
                    var a = 1f
                    if (anim) {
                        val f = ((t - i * 500L) % 1500L + 1500L) % 1500L / 1500f
                        dy = -1.5f + 4.5f * f
                        a = if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f
                    }
                    if (i % 2 == 0) {
                        line.color = RAIN_C
                        line.strokeWidth = 1.4f
                        line.alpha = (255 * a).toInt()
                        c.drawLine(xs[i], 18.6f + dy, xs[i] - 0.6f, 20.8f + dy, line)
                    } else {
                        fill.color = Color.WHITE
                        fill.alpha = (255 * a).toInt()
                        c.drawCircle(xs[i], 19.6f + dy, 0.95f, fill)
                    }
                }
                line.alpha = 255
                fill.alpha = 255
            }
            STORM -> {
                clouds(c, t, anim, -3.5f, true)
                fall(c, t, anim, floatArrayOf(9.3f, 12.8f, 16.3f), 18.6f, 2.2f, 0.6f, 1100L, 370L, 5f, 1.4f, RAIN_C)
                // Blitz zuckt ab und zu doppelt auf
                val f = (t % 3000L) / 3000f
                val on = !anim || f < 0.05f || (f > 0.1f && f < 0.14f)
                fill.color = 0xFFFFD84D.toInt()
                fill.alpha = if (on) 255 else 90
                pathTmp.reset()
                pathTmp.moveTo(13.4f, 15.2f); pathTmp.lineTo(10.6f, 19.6f); pathTmp.lineTo(12.6f, 19.6f); pathTmp.lineTo(11.4f, 23f)
                pathTmp.lineTo(15f, 18.2f); pathTmp.lineTo(12.9f, 18.2f); pathTmp.lineTo(14.4f, 15.2f); pathTmp.close()
                c.drawPath(pathTmp, fill)
                fill.alpha = 255
            }
            WIND -> {
                // drei Windboegen ziehen durch, die Enden rollen sich ein
                val lines = arrayOf(floatArrayOf(3f, 8.2f, 15.5f, -1f, 1500f), floatArrayOf(2f, 12.5f, 19f, 1f, 1900f), floatArrayOf(4.5f, 16.8f, 13.5f, 1f, 1300f))
                for (i in lines.indices) {
                    val L = lines[i]
                    val o = if (anim) swing(t, (L[4] * 2).toLong(), -0.9f, 1.1f, i * 400L) else 0f
                    val r = 2.1f
                    line.color = if (i == 1) Color.WHITE else 0xFFD6E4F5.toInt()
                    line.strokeWidth = if (i == 1) 1.6f else 1.3f
                    line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 3.5f), if (anim) ((t / 60f) % 25f) * -1f else 0f)
                    pathTmp.reset()
                    pathTmp.moveTo(L[0] + o, L[1])
                    pathTmp.lineTo(L[2] + o, L[1])
                    if (L[3] < 0) pathTmp.arcTo(android.graphics.RectF(L[2] + o - r, L[1] - 2 * r, L[2] + o + r, L[1]), 90f, 225f)
                    else pathTmp.arcTo(android.graphics.RectF(L[2] + o - r, L[1], L[2] + o + r, L[1] + 2 * r), -90f, -225f)
                    line.style = Paint.Style.STROKE
                    c.drawPath(pathTmp, line)
                    line.pathEffect = null
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
            MOON -> moon(c, t, anim, 13.2f, 12f, 1f)
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
