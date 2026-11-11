package de.jugie.firelauncher

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Property
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout

/**
 * Abstrakter Hintergrund: dunkler Verlauf, grosse weiche Farbformen, ein feiner Lichtstreifen
 * und eine Vignette. Die Formen sind fertige GPU-Ebenen, die nur verschoben, gedreht und
 * skaliert werden; das kostet auf dem Stick kaum Leistung.
 */
class Aurora(ctx: Context, private val k: Float) : FrameLayout(ctx) {

    companion object {
        val NAMES = arrayOf("Aurora", "Ozean", "Violett", "Mitternacht")
    }

    private val anims = ArrayList<Animator>()
    private var animated = true
    private var running = false

    fun configure(preset: Int, animate: Boolean) {
        for (a in anims) a.cancel()
        anims.clear()
        removeAllViews()
        animated = animate
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Ui.MIDNIGHT, Ui.NAVY, Ui.MIDNIGHT)
        )
        when (preset) {
            1 -> {
                blob(Ui.OCEAN, 0.95f, 820, -220f, -260f, 520f, 260f, 1.7f, 2.2f, 1.5f, -12f, 14f, 46000L)
                blob(Ui.OCEAN, 0.70f, 760, 1150f, 420f, -460f, -240f, 2.0f, 1.6f, 1.3f, 20f, -10f, 58000L)
                blob(Ui.CYAN, 0.26f, 640, 620f, 560f, 380f, -320f, 1.5f, 2.0f, 1.8f, -8f, 18f, 67000L)
            }
            2 -> {
                blob(Ui.VIOLET, 0.95f, 820, -200f, -240f, 540f, 240f, 1.7f, 2.2f, 1.5f, -12f, 14f, 46000L)
                blob(Ui.VIOLET, 0.75f, 760, 1180f, 440f, -480f, -260f, 2.0f, 1.6f, 1.3f, 20f, -10f, 58000L)
                blob(Ui.OCEAN, 0.55f, 700, 560f, 600f, 400f, -300f, 1.5f, 2.0f, 1.7f, -8f, 18f, 67000L)
                blob(Ui.CYAN, 0.12f, 560, 1300f, -200f, -300f, 260f, 1.4f, 1.8f, 1.6f, 0f, 20f, 73000L)
            }
            3 -> {
                blob(Ui.NAVY, 1.0f, 860, -200f, -260f, 500f, 240f, 1.8f, 2.3f, 1.5f, -10f, 12f, 52000L)
                blob(Ui.OCEAN, 0.38f, 740, 1150f, 460f, -440f, -240f, 1.9f, 1.5f, 1.3f, 16f, -8f, 64000L)
                blob(Ui.VIOLET, 0.30f, 680, 560f, 620f, 360f, -280f, 1.5f, 1.9f, 1.7f, -8f, 14f, 71000L)
            }
            else -> {
                blob(Ui.OCEAN, 0.90f, 820, -220f, -260f, 520f, 260f, 1.7f, 2.2f, 1.5f, -12f, 14f, 46000L)
                blob(Ui.VIOLET, 0.85f, 780, 1160f, 430f, -480f, -260f, 2.0f, 1.6f, 1.3f, 20f, -10f, 58000L)
                blob(Ui.CYAN, 0.20f, 620, 600f, 600f, 400f, -320f, 1.5f, 2.0f, 1.8f, -8f, 18f, 67000L)
            }
        }
        streak()
        val vig = View(context)
        val vd = GradientDrawable()
        vd.gradientType = GradientDrawable.RADIAL_GRADIENT
        vd.gradientRadius = 1250f * k
        vd.colors = intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt())
        vig.background = vd
        addView(vig, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (running) start()
    }

    private fun blob(
        c: Int, a: Float, size: Int, x: Float, y: Float, dx: Float, dy: Float,
        s1: Float, s2: Float, stretch: Float, r1: Float, r2: Float, dur: Long
    ) {
        val px = (size * k).toInt()
        val v = View(context)
        v.background = Ui.radial(c, px)
        v.alpha = a
        v.translationX = x * k
        v.translationY = y * k
        v.scaleX = s1 * stretch
        v.scaleY = s1
        v.rotation = r1
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addView(v, LayoutParams(px, px))
        loop(v, View.TRANSLATION_X, x * k, (x + dx) * k, dur)
        loop(v, View.TRANSLATION_Y, y * k, (y + dy) * k, (dur * 1.31f).toLong())
        loop(v, View.SCALE_X, s1 * stretch, s2 * stretch, (dur * 0.83f).toLong())
        loop(v, View.SCALE_Y, s1, s2, (dur * 0.83f).toLong())
        loop(v, View.ROTATION, r1, r2, (dur * 1.17f).toLong())
    }

    /** Feiner diagonaler Lichtstreifen als dezente Reflexion. */
    private fun streak() {
        val v = View(context)
        v.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00FFFFFF, 0x12FFFFFF, 0x00FFFFFF)
        )
        v.rotation = -24f
        v.translationX = -200f * k
        v.translationY = 120f * k
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addView(v, LayoutParams((2600 * k).toInt(), (300 * k).toInt(), Gravity.CENTER))
        loop(v, View.TRANSLATION_Y, 120f * k, -160f * k, 61000L)
        loop(v, View.ALPHA, 0.5f, 1f, 37000L)
    }

    private fun loop(v: View, prop: Property<View, Float>, from: Float, to: Float, dur: Long) {
        val a = ObjectAnimator.ofFloat(v, prop, from, to)
        a.duration = dur
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.interpolator = AccelerateDecelerateInterpolator()
        anims.add(a)
    }

    fun start() {
        running = true
        if (!animated) return
        for (a in anims) {
            if (!a.isStarted) a.start() else if (a.isPaused) a.resume()
        }
    }

    fun stop() {
        running = false
        for (a in anims) if (a.isStarted) a.pause()
    }
}
