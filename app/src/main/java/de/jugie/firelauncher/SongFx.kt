package de.jugie.firelauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

/** Leuchtende Hauptfarbe eines Bildes (z. B. Cover), hell genug fuer Licht auf dunklem Grund. */
object Tone {
    fun of(b: Bitmap?, fallback: Int): Int {
        if (b == null || b.width <= 0 || b.height <= 0) return fallback
        val hsv = FloatArray(3)
        var best = fallback
        var score = -1f
        val sx = Math.max(1, b.width / 24)
        val sy = Math.max(1, b.height / 24)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val sc = hsv[1] * hsv[2] * (if (hsv[2] > 0.25f) 1f else 0.2f)
                if (sc > score) {
                    score = sc
                    best = c
                }
                x += sx
            }
            y += sy
        }
        Color.colorToHSV(best, hsv)
        // fast graue Cover: dann lieber die gewohnte Farbe
        if (hsv[1] < 0.18f) return fallback
        hsv[2] = Math.max(hsv[2], 0.85f)
        hsv[1] = Math.max(hsv[1], 0.55f)
        return Color.HSVToColor(hsv)
    }

    fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
}

/**
 * Farbwelle beim Liedwechsel: Licht in der Farbe des neuen Covers breitet sich kreisfoermig vom Cover aus
 * und verblasst dabei.
 */
class ColorWave(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null
    private var cx = 0f
    private var cy = 0f
    private var maxR = 0f
    private var r = 0f
    private var a = 0f
    private val ease = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)

    init {
        isFocusable = false
    }

    fun play(color: Int, x: Float, y: Float, radius: Float, ms: Long = 1300) {
        if (!Ui.anims) return
        anim?.cancel()
        cx = x
        cy = y
        maxR = radius
        p.shader = RadialGradient(x, y, radius * 0.85f,
            intArrayOf(Tone.alpha(color, 0.55f), Tone.alpha(color, 0.15f), Tone.alpha(color, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = ms
        va.addUpdateListener {
            val f = it.animatedFraction
            r = maxR * ease.getInterpolation(f)
            a = if (f < 0.6f) 1f - 0.15f * (f / 0.6f) else 0.85f * (1f - (f - 0.6f) / 0.4f)
            invalidate()
        }
        va.start()
        anim = va
    }

    override fun onDraw(c: Canvas) {
        if (r <= 0f || a <= 0f) return
        p.alpha = (255 * a).toInt()
        c.drawCircle(cx, cy, r, p)
    }
}

/** Ring um das Cover, der sich beim Liedwechsel ausbreitet und verblasst. */
class RingPulse(ctx: Context, private val k: Float, private val radius: Float) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    init {
        alpha = 0f
        isFocusable = false
    }

    fun play(color: Int) {
        if (!Ui.anims) return
        p.color = color
        p.strokeWidth = 3f * k
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        alpha = 0.9f
        animate().scaleX(1.55f).scaleY(1.55f).alpha(0f).setStartDelay(150).setDuration(900)
            .setInterpolator(PathInterpolator(0f, 0f, 0.58f, 1f)).start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val h = p.strokeWidth / 2f
        box.set(h, h, width - h, height - h)
        c.drawRoundRect(box, radius * k, radius * k, p)
    }
}

/** Kleine Bausteine fuer die Liedwechsel-Animation "Farbwelle". */
object SongFx {
    private val spring = PathInterpolator(0.2f, 1.2f, 0.35f, 1f)
    private val soft = PathInterpolator(0.2f, 1f, 0.35f, 1f)

    /** Cover ploppt auf: klein und durchsichtig, kurz etwas zu gross, dann normal. */
    fun pop(v: View, target: Float = 1f) {
        if (!Ui.anims) return
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 700
        va.addUpdateListener {
            val f = it.animatedFraction
            val s: Float
            if (f < 0.7f) {
                val e = spring.getInterpolation(f / 0.7f)
                s = 0.55f + 0.5f * e
                v.alpha = Math.min(1f, f / 0.7f)
            } else {
                s = 1.05f - 0.05f * ((f - 0.7f) / 0.3f)
                v.alpha = 1f
            }
            v.scaleX = s * target
            v.scaleY = s * target
        }
        va.start()
    }

    /** Texte ziehen sich zusammen: weite Buchstaben werden normal, dabei blenden sie ein. */
    fun texts(views: List<TextView>, base: Long = 250, step: Long = 110) {
        if (!Ui.anims) return
        views.forEachIndexed { i, tv ->
            val orig = (tv.getTag(R.id.song_ls) as? Float) ?: tv.letterSpacing.also { tv.setTag(R.id.song_ls, it) }
            tv.letterSpacing = orig + 0.35f
            tv.alpha = 0f
            val va = ValueAnimator.ofFloat(0f, 1f)
            va.duration = 700
            va.startDelay = base + i * step
            va.interpolator = soft
            va.addUpdateListener {
                val f = it.animatedValue as Float
                tv.letterSpacing = orig + 0.35f * (1f - f)
                tv.alpha = Math.min(1f, it.animatedFraction * 1.4f)
            }
            va.start()
        }
    }
}
