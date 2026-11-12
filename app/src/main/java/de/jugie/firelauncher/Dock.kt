package de.jugie.firelauncher

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Kachel der App-Leiste wie im Entwurf: quadratisches Glas mit Logo.
 * Ausgewaehlt: Rahmen und Schein in der App-Farbe (fertige Glasbilder), ein Lichtpunkt laeuft
 * in 6 s um den Rahmen, das Logo federt einmal nach, die Spiegelung oben links gleitet mit.
 */
class DockTile(ctx: Context, val app: AppEntry, private val k: Float) : FrameLayout(ctx) {
    companion object {
        const val SIZE = 200

        /** Rahmenfarben, passend zu den Glasbildern */
        fun ring(kind: String): Int = when (kind) {
            "youtube" -> Color.rgb(255, 32, 32)
            "twitch" -> Color.rgb(150, 72, 255)
            "netflix" -> Color.rgb(232, 20, 20)
            "prime" -> Color.rgb(30, 144, 255)
            "wow" -> Color.rgb(80, 215, 235)
            "disney" -> Color.rgb(40, 78, 215)
            "spotify" -> Color.rgb(30, 215, 96)
            else -> Color.rgb(225, 230, 240)
        }
    }

    private val kind = Content.kind(app)
    private val normal = ImageView(ctx)
    private val lit = ImageView(ctx)
    private val comet = CometRing(ctx, k, ring(kind))
    private val sheen = View(ctx)
    private val logoBox = FrameLayout(ctx)
    private var logoLit: ImageView? = null
    private var logoPlain: ImageView? = null
    private val bounceCurve = PathInterpolator(0.3f, 0.7f, 0.4f, 1f)
    var onSelect: ((View) -> Unit)? = null

    private fun px(v: Int): Int = (v * k).toInt()

    init {
        clipChildren = false
        clipToPadding = false
        val g = px(SIZE + 120)
        normal.setImageResource(R.drawable.glass_tile2)
        normal.scaleType = ImageView.ScaleType.FIT_XY
        addView(normal, LayoutParams(g, g, Gravity.CENTER))
        lit.setImageResource(
            when (kind) {
                "youtube" -> R.drawable.glass_tile2_youtube
                "twitch" -> R.drawable.glass_tile2_twitch
                "netflix" -> R.drawable.glass_tile2_netflix
                "prime" -> R.drawable.glass_tile2_prime
                "wow" -> R.drawable.glass_tile2_wow
                "disney" -> R.drawable.glass_tile2_disney
                "spotify" -> R.drawable.glass_tile2_spotify
                else -> R.drawable.glass_tile2_other
            }
        )
        lit.scaleType = ImageView.ScaleType.FIT_XY
        lit.alpha = 0f
        addView(lit, LayoutParams(g, g, Gravity.CENTER))

        val cs = px(SIZE + 2 * CometRing.PAD)
        comet.visibility = View.INVISIBLE
        addView(comet, LayoutParams(cs, cs, Gravity.CENTER))

        // Spiegelung oben links, auf die Glasform zugeschnitten
        val clip = FrameLayout(ctx)
        clip.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, 24f * k)
            }
        }
        clip.clipToOutline = true
        sheen.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            shape = GradientDrawable.OVAL
            gradientRadius = 140f * k
            colors = intArrayOf(0x80FFFFFF.toInt(), 0x00FFFFFF)
        }
        sheen.alpha = 0.28f
        clip.addView(sheen, LayoutParams(px(280), px(230)).apply {
            leftMargin = px(-80)
            topMargin = px(-110)
        })
        addView(clip, LayoutParams(px(SIZE), px(SIZE), Gravity.CENTER))

        buildLogo(ctx)
        addView(logoBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        isFocusable = true
        contentDescription = app.label
        setOnFocusChangeListener { _, has -> focus(has) }
    }

    private fun buildLogo(ctx: Context) {
        fun img(res: Int, w: Int, h: Int): ImageView {
            val iv = ImageView(ctx)
            iv.setImageResource(res)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            logoBox.addView(iv, LayoutParams(px(w), px(h), Gravity.CENTER))
            return iv
        }
        when (kind) {
            "youtube" -> img(R.drawable.tile_logo_youtube, 142, 33)
            "netflix" -> img(R.drawable.tile_logo_netflix, 134, 37)
            "spotify" -> img(R.drawable.tile_logo_spotify, 148, 45)
            "prime" -> img(R.drawable.logo_prime, 98, 94)
            "wow" -> img(R.drawable.logo_wow, 137, 39)
            "disney" -> img(R.drawable.logo_disney, 139, 78)
            "twitch" -> {
                // weisses App-Symbol, bei Auswahl blendet die Sprechblase in Lila ueber
                logoPlain = img(R.drawable.tile_logo_twitch, 114, 113)
                logoLit = img(R.drawable.tile_logo_twitch_lit, 114, 113).also { it.alpha = 0f }
            }
            else -> {
                val iv = ImageView(ctx)
                iv.setImageDrawable(app.icon ?: app.banner)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, v.width * 0.23f)
                    }
                }
                iv.clipToOutline = true
                logoBox.addView(iv, LayoutParams(px(90), px(90), Gravity.CENTER))
            }
        }
    }

    private fun focus(has: Boolean) {
        val d = Ui.d(300)
        animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
            .translationY(if (has) -6f * k else 0f).setDuration(d).setInterpolator(Ui.smooth).start()
        lit.animate().alpha(if (has) 1f else 0f).setDuration(d).start()
        normal.animate().alpha(if (has) 0f else 1f).setDuration(d).start()
        logoLit?.animate()?.alpha(if (has) 1f else 0f)?.setDuration(d)?.start()
        logoPlain?.animate()?.alpha(if (has) 0f else 1f)?.setDuration(d)?.start()
        if (has) {
            comet.visibility = View.VISIBLE
            comet.start()
            if (Ui.anims) bounce()
            onSelect?.invoke(this)
        } else {
            comet.stop()
            comet.visibility = View.INVISIBLE
        }
    }

    /** Logo federt einmal nach: 1 → 1,14 → 0,95 → 1,04 → 1 */
    private fun bounce() {
        val kf = arrayOf(
            Keyframe.ofFloat(0f, 1f), Keyframe.ofFloat(0.3f, 1.14f), Keyframe.ofFloat(0.55f, 0.95f),
            Keyframe.ofFloat(0.75f, 1.04f), Keyframe.ofFloat(1f, 1f)
        )
        val a = ObjectAnimator.ofPropertyValuesHolder(
            logoBox,
            PropertyValuesHolder.ofKeyframe(View.SCALE_X, *kf),
            PropertyValuesHolder.ofKeyframe(View.SCALE_Y, *kf)
        )
        a.duration = 650
        a.startDelay = 80
        a.interpolator = bounceCurve
        a.start()
    }

    /** Spiegelung zur ausgewaehlten Kachel hin neigen (dx in 1920er-Pixeln). */
    fun setSheen(dx: Float, selected: Boolean) {
        sheen.animate().translationX(dx * k).translationY(if (selected) 10f * k else 0f)
            .alpha(if (selected) 0.55f else 0.28f).setDuration(Ui.d(700)).setInterpolator(Ui.smooth).start()
    }

    /** OK: Kachel drueckt sich kurz ein, danach startet die App. */
    fun press(then: () -> Unit) {
        if (!Ui.anims) {
            then()
            return
        }
        animate().scaleX(0.93f).scaleY(0.93f).translationY(-2f * k).setDuration(140)
            .setInterpolator(PathInterpolator(0f, 0f, 0.58f, 1f))
            .withEndAction {
                then()
                postDelayed({
                    val s = if (isFocused) 1.06f else 1f
                    animate().scaleX(s).scaleY(s).translationY(if (isFocused) -6f * k else 0f).setDuration(300).start()
                }, 400)
            }.start()
    }

    fun stopEffects() = comet.stop()
    fun resumeEffects() {
        if (isFocused) comet.start()
    }
}

/** Lichtpunkt mit Schweif in der App-Farbe, der einmal in 6 s um den Rahmen laeuft. */
class CometRing(ctx: Context, private val k: Float, color: Int) : View(ctx) {
    companion object {
        const val PAD = 14
        const val LAP = 6000L
    }

    private val sharp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val m = Matrix()
    private var shader: SweepGradient? = null
    private val c0 = color and 0x00FFFFFF
    private val cTail = (0xD9 shl 24) or c0
    private var running = false
    private val t0 = SystemClock.uptimeMillis()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = PAD * k + 2f * k
        rect.set(inset, inset, w - inset, h - inset)
        // CSS-Kegelverlauf beginnt oben, SweepGradient rechts: deshalb 90 Grad zurueckdrehen
        shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(c0, c0, cTail, Color.WHITE, 0x00FFFFFF),
            floatArrayOf(0f, 190f / 360f, 315f / 360f, 352f / 360f, 1f)
        )
        sharp.shader = shader
        soft.shader = shader
    }

    fun start() {
        if (running) return
        running = true
        invalidate()
    }

    fun stop() {
        running = false
    }

    override fun onDraw(c: Canvas) {
        val s = shader ?: return
        val angle = if (Ui.anims) ((SystemClock.uptimeMillis() - t0) % LAP) * 360f / LAP else 0f
        m.setRotate(angle - 90f, width / 2f, height / 2f)
        s.setLocalMatrix(m)
        val r = 22f * k
        // weicher Schein in drei Stufen, dann die scharfe Linie
        for ((w, a) in arrayOf(18f to 50, 11f to 90, 7f to 140)) {
            soft.strokeWidth = w * k
            soft.alpha = a
            c.drawRoundRect(rect, r, r, soft)
        }
        sharp.strokeWidth = 4f * k
        c.drawRoundRect(rect, r, r, sharp)
        if (running && Ui.anims) postInvalidateOnAnimation()
    }
}
