package de.jugie.firelauncher

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout

/**
 * Animierter Hintergrund: eine Grundfarbe, die weich zur Farbe der ausgewaehlten App wechselt,
 * und drei grosse, langsam wandernde Lichtflecken. Die Flecken sind fertige GPU-Ebenen,
 * die nur verschoben und skaliert werden, deshalb kostet das kaum Leistung.
 */
class Aurora(ctx: Context, private val k: Float) : FrameLayout(ctx) {

    private val anims = ArrayList<Animator>()
    private var color = Ui.BG
    private var colorAnim: ValueAnimator? = null

    init {
        setBackgroundColor(color)
        blob(0xFFFFFFFF.toInt(), 0.13f, 720, -260f, -300f, 620f, 240f, 1.9f, 2.4f, 27000L)
        blob(0xFF6C8DFF.toInt(), 0.20f, 680, 1180f, 420f, -560f, -260f, 2.3f, 1.8f, 35000L)
        blob(0xFFFFFFFF.toInt(), 0.09f, 600, 520f, 640f, 420f, -340f, 1.8f, 2.5f, 43000L)
    }

    private fun blob(c: Int, a: Float, size: Int, x: Float, y: Float, dx: Float, dy: Float, s1: Float, s2: Float, dur: Long) {
        val px = (size * k).toInt()
        val v = View(context)
        val d = GradientDrawable()
        d.gradientType = GradientDrawable.RADIAL_GRADIENT
        d.gradientRadius = px / 2f
        d.colors = intArrayOf(c, c and 0x00FFFFFF)
        v.background = d
        v.alpha = a
        v.translationX = x * k
        v.translationY = y * k
        v.scaleX = s1
        v.scaleY = s1
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addView(v, LayoutParams(px, px))
        loop(v, View.TRANSLATION_X, x * k, (x + dx) * k, dur)
        loop(v, View.TRANSLATION_Y, y * k, (y + dy) * k, (dur * 1.31f).toLong())
        loop(v, View.SCALE_X, s1, s2, (dur * 0.83f).toLong())
        loop(v, View.SCALE_Y, s1, s2, (dur * 0.83f).toLong())
    }

    private fun loop(v: View, prop: android.util.Property<View, Float>, from: Float, to: Float, dur: Long) {
        val a = ObjectAnimator.ofFloat(v, prop, from, to)
        a.duration = dur
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.interpolator = AccelerateDecelerateInterpolator()
        anims.add(a)
    }

    /** Grundfarbe weich wechseln. */
    fun setTint(target: Int) {
        if (target == color) return
        colorAnim?.cancel()
        val from = color
        color = target
        val a = ValueAnimator.ofArgb(from, target)
        a.duration = 520
        a.addUpdateListener { setBackgroundColor(it.animatedValue as Int) }
        a.start()
        colorAnim = a
    }

    fun start() {
        for (a in anims) {
            if (!a.isStarted) a.start() else if (a.isPaused) a.resume()
        }
    }

    fun stop() {
        for (a in anims) if (a.isStarted) a.pause()
    }
}
