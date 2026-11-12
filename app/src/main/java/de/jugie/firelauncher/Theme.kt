package de.jugie.firelauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Gewaehltes Design. GTA VI: gleicher Aufbau, eigene Farben, Schrift und Effekte. */
object Theme {
    @Volatile var gta = false

    const val PINK = 0xFFFF3FA4.toInt()
    const val ORANGE = 0xFFFFA05A.toInt()
    const val PEACH = 0xFFFFC08A.toInt()
    const val CYAN = 0xFF8AF4FF.toInt()
    const val SHADOW = 0xE65A1E78.toInt()

    /** Release von GTA VI: 19.11.2026, 0 Uhr deutsche Zeit */
    const val RELEASE = 1795042800000L

    val bold: Typeface by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC) }
    val italic: Typeface by lazy { Typeface.create("sans-serif-condensed", Typeface.ITALIC) }

    fun load(c: Context) {
        gta = Prefs.design(c) == 1
    }

    /** Ordner fuer die per Handy hochgeladenen Hintergruende. */
    fun bgDir(c: Context): File = File(c.filesDir, "bg").apply { mkdirs() }

    fun bgFiles(c: Context): List<File> =
        (bgDir(c).listFiles() ?: emptyArray()).filter { it.isFile && it.length() > 0 }.sortedBy { it.name }

    /** Text mit Verlauf (oben hell, unten pink) wie die Titel im Entwurf. */
    fun sunsetText(t: TextView) {
        t.post {
            val h = t.textSize
            t.paint.shader = LinearGradient(
                0f, 0f, 0f, h * 1.1f,
                intArrayOf(0xFFFFE6A0.toInt(), 0xFFFF9A5B.toInt(), PINK), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
            )
            t.invalidate()
        }
    }

    /** Countdown-Text, null nach dem Release. */
    fun countdown(now: Long = System.currentTimeMillis()): String? {
        val left = RELEASE - now
        if (left <= 0) return null
        val t = left / 1000
        val d = t / 86400
        val h = t % 86400 / 3600
        val m = t % 3600 / 60
        val s = t % 60
        val days = if (d > 0) d.toString() + (if (d == 1L) " TAG  " else " TAGE  ") else ""
        return days + String.format("%02d:%02d:%02d", h, m, s)
    }
}

/**
 * Hintergrund im GTA-Design: zufaellige Bilder wie die Ladebilder im Spiel, langsamer Zoom und
 * Schwenk, weiche Ueberblendung, ein Lichtstreif bei jedem Wechsel und Palmenschatten im Wind.
 */
class GtaBackground(ctx: Context, private val k: Float) : FrameLayout(ctx) {
    private val a = ImageView(ctx)
    private val b = ImageView(ctx)
    private var front = a
    private val leak = View(ctx)
    private val palms = ImageView(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var files: List<File> = emptyList()
    private var idx = -1
    private var running = false
    private var sway: ObjectAnimator? = null
    private var turn = 0
    private val next = Runnable { advance() }

    init {
        setBackgroundColor(0xFF1A0B2E.toInt())
        for (iv in listOf(a, b)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.alpha = 0f
            addView(iv, LayoutParams(-1, -1))
        }
        leak.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00FF8C5A, 0x8CFF965A.toInt(), 0x80FF3FA4.toInt(), 0x00FF3FA4)
        )
        leak.alpha = 0f
        leak.rotation = 18f
        addView(leak, LayoutParams((900 * k).toInt(), (1500 * k).toInt()).apply { topMargin = (-200 * k).toInt() })
        palms.setImageResource(R.drawable.palm_shadow)
        palms.scaleType = ImageView.ScaleType.FIT_XY
        addView(palms, LayoutParams(-1, -1))
        // oben und unten abdunkeln, damit Uhr, Wetter und Knoepfe lesbar bleiben
        val scrim = View(ctx)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xC71A082E.toInt(), 0x401A082E, 0x1F1A082E, 0x8C1A082E.toInt(), 0xE6140624.toInt())
        )
        addView(scrim, LayoutParams(-1, -1))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        palms.pivotX = w.toFloat()
        palms.pivotY = 0f
    }

    fun start() {
        running = true
        files = Theme.bgFiles(context)
        if (front.drawable == null) advance() else schedule()
        if (Ui.anims && sway == null) {
            sway = ObjectAnimator.ofFloat(palms, View.ROTATION, 0f, 1.6f, 0f).apply {
                duration = 9000
                repeatCount = ValueAnimator.INFINITE
                interpolator = PathInterpolator(0.45f, 0f, 0.55f, 1f)
                start()
            }
        } else sway?.resume()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(next)
        sway?.pause()
    }

    private fun schedule() {
        handler.removeCallbacks(next)
        if (running) handler.postDelayed(next, 12000)
    }

    private fun advance() {
        val list = files
        val w = resources.displayMetrics.widthPixels
        if (list.isEmpty()) {
            if (front.drawable == null) show(BitmapFactory.decodeResource(resources, R.drawable.gta_fallback))
            return
        }
        var n = (Math.random() * list.size).toInt()
        if (list.size > 1 && n == idx) n = (n + 1) % list.size
        idx = n
        val f = list[n]
        pool.execute {
            val bmp = decode(f, w)
            handler.post { if (bmp != null) show(bmp); schedule() }
        }
    }

    private fun decode(f: File, w: Int): Bitmap? = try {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= w) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        BitmapFactory.decodeFile(f.path, o2)
    } catch (e: Throwable) {
        null
    }

    private fun show(bmp: Bitmap?) {
        if (bmp == null) return
        val back = if (front === a) b else a
        back.animate().cancel()
        back.setImageBitmap(bmp)
        // Zoom und Schwenk, jedes Bild in eine andere Richtung
        val dirs = arrayOf(floatArrayOf(-24f, -10f), floatArrayOf(22f, 8f), floatArrayOf(-12f, 14f), floatArrayOf(18f, -12f))
        val d = dirs[turn % dirs.size]
        turn++
        back.scaleX = 1f
        back.scaleY = 1f
        back.translationX = 0f
        back.translationY = 0f
        val d1 = Ui.d(1600)
        back.animate().cancel()
        if (Ui.anims) {
            back.alpha = 0f
            ObjectAnimator.ofFloat(back, View.ALPHA, 0f, 1f).setDuration(1600).start()
            back.animate().scaleX(1.12f).scaleY(1.12f).translationX(d[0] * k).translationY(d[1] * k)
                .setDuration(14000).setInterpolator(LinearInterpolator()).start()
        } else back.alpha = 1f
        val old = front
        if (d1 > 0) ObjectAnimator.ofFloat(old, View.ALPHA, old.alpha, 0f).setDuration(d1).start() else old.alpha = 0f
        front = back
        sweep()
    }

    /** Lichtstreif zieht einmal schraeg durchs Bild. */
    private fun sweep() {
        if (!Ui.anims) return
        val w = resources.displayMetrics.widthPixels.toFloat()
        leak.translationX = -1100f * k
        leak.alpha = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 2200
        anim.interpolator = PathInterpolator(0.3f, 0.1f, 0.3f, 1f)
        anim.addUpdateListener {
            val f = it.animatedValue as Float
            leak.translationX = -1100f * k + (w + 2200f * k) * f
            leak.alpha = if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f
        }
        anim.start()
    }
}

/** Kleine Neon-Hilfen fuer Texte im GTA-Design. */
object Neon {
    fun greet(t: TextView, k: Float) {
        t.typeface = Theme.bold
        t.isAllCaps = true
        t.letterSpacing = 0.07f
        t.setTextColor(0xFFFFF4FB.toInt())
        t.setShadowLayer(22f * k, 0f, 0f, Theme.PINK)
        t.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /** Leuchtreklame flackert ab und zu kurz. */
    fun flicker(t: View): ValueAnimator? {
        if (!Ui.anims) return null
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 9000
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val f = it.animatedValue as Float
            t.alpha = if ((f in 0.18f..0.20f) || (f in 0.22f..0.24f) || (f in 0.61f..0.63f)) 0.5f else 1f
        }
        a.start()
        return a
    }

    fun shadow(t: TextView, k: Float, color: Int = Theme.SHADOW) {
        t.setShadowLayer(0.01f, 4f * k, 4f * k, color)
    }

    @Suppress("unused")
    fun white(t: TextView) = t.setTextColor(Color.WHITE)
}
