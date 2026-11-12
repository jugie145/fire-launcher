package de.jugie.firelauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * App-Start ohne Ladekreis: Unsere Animation liegt ueber allem, die App startet darunter.
 * Ist sie bereit (HomeService beobachtet ihre Fenster), blendet die Animation aus.
 * Zufaellig eine von drei: Warp, Vorhang, Liquid Glass.
 */
object LaunchFx {
    const val WARP = 0
    const val CURTAIN = 1
    const val LIQUID = 2

    class Look(val color: Int, val logo: Bitmap?, val white: Bitmap?, val w: Float, val h: Float, val kind: String)

    private fun bmp(ctx: Context, res: Int): Bitmap? = try { BitmapFactory.decodeResource(ctx.resources, res) } catch (e: Throwable) { null }

    private fun bmp(d: Drawable?, w: Int, h: Int): Bitmap? {
        if (d == null) return null
        return try {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            b
        } catch (e: Throwable) { null }
    }

    fun look(ctx: Context, app: AppEntry): Look {
        val kind = Content.kind(app)
        fun l(color: Int, logo: Int, white: Int, w: Int, h: Int, sc: Float) =
            Look(color, bmp(ctx, logo), bmp(ctx, white), w * sc, h * sc, kind)
        return when (kind) {
            "youtube" -> l(0xFFFF0000.toInt(), R.drawable.tile_logo_youtube, R.drawable.gta_logo_youtube_w, 142, 33, 2.6f)
            "twitch" -> l(0xFF9246FF.toInt(), R.drawable.tile_logo_twitch_lit, R.drawable.gta_logo_twitch_w, 114, 113, 1.6f)
            "netflix" -> l(0xFFE50000.toInt(), R.drawable.tile_logo_netflix, R.drawable.gta_logo_netflix_w, 134, 37, 2.6f)
            "prime" -> l(0xFF2896D3.toInt(), R.drawable.logo_prime, R.drawable.gta_logo_prime_w, 98, 94, 1.9f)
            "wow" -> l(0xFF02AAB2.toInt(), R.drawable.logo_wow, R.drawable.gta_logo_wow_w, 137, 39, 2.5f)
            "disney" -> l(0xFF284ED7.toInt(), R.drawable.logo_disney, R.drawable.gta_logo_disney_w, 139, 78, 2.1f)
            "spotify" -> l(0xFF1ED860.toInt(), R.drawable.tile_logo_spotify, R.drawable.gta_logo_spotify_w, 148, 45, 2.4f)
            else -> {
                // andere Apps: Banner (breit) oder Symbol, Farbe aus dem Bild
                val wide = app.banner != null
                val b = if (wide) bmp(app.banner, 320, 180) else bmp(app.icon, 192, 192)
                val c = Tone.of(b, Ui.CYAN)
                if (wide) Look(c, b, b, 480f, 270f, kind) else Look(c, b, b, 260f, 260f, kind)
            }
        }
    }

    /** Wartezeit nach dem ersten Fenster, weil manche Apps danach noch einen eigenen Ladebildschirm zeigen. */
    fun extraWait(kind: String): Long = when (kind) {
        "netflix" -> 2000L
        "disney" -> 1500L
        "prime", "wow" -> 1200L
        else -> 0L
    }

    /**
     * Startet die App mit Animation. from = die Kachel (fuer Liquid Glass), launch = das eigentliche Oeffnen.
     * Ohne Bedienungshilfe oder mit ausgeschalteten Animationen wird ganz normal geoeffnet.
     */
    fun open(act: Activity, app: AppEntry, from: View?, launch: () -> Unit) {
        if (!Ui.anims || !Prefs.launchFx(act)) {
            launch()
            return
        }
        val dm = act.resources.displayMetrics
        val k = dm.widthPixels / 1920f
        var tile = RectF(860f, 440f, 1060f, 640f)
        if (from != null && from.width > 0) {
            val loc = IntArray(2)
            from.getLocationOnScreen(loc)
            tile = RectF(loc[0] / k, loc[1] / k, (loc[0] + from.width * from.scaleX) / k, (loc[1] + from.height * from.scaleY) / k)
        }
        val v = LaunchOverlay(act, look(act, app), (Math.random() * 3).toInt().coerceIn(0, 2), tile)
        if (!HomeService.showLaunch(v, app.pkg, extraWait(Content.kind(app)))) {
            launch()
            return
        }
        // erst zeichnen lassen, dann startet die App darunter
        v.postDelayed({ launch() }, 120)
    }
}

/** Die Start-Animation selbst, gezeichnet im 1920 x 1080-Raster. */
class LaunchOverlay(ctx: Context, private val look: LaunchFx.Look, private val variant: Int, private val tile: RectF) : View(ctx) {
    private var start = 0L
    private var t = 0f
    private var ready = Float.MAX_VALUE
    private var onGone: (() -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val c0 = look.color
    private val rnd = java.util.Random()
    private class Streak(val a: Float, val d: Float, val dur: Float, val w: Float, val o: Float)
    private val streaks = List(72) { Streak((rnd.nextFloat() * 2 * PI).toFloat(), rnd.nextFloat() * 1600f, 900f + rnd.nextFloat() * 800f, 60f + rnd.nextInt(140), 0.5f + rnd.nextFloat() * 0.5f) }
    private val eSoft = PathInterpolator(0.2f, 1f, 0.35f, 1f)
    private val eIn = PathInterpolator(0.5f, 0f, 1f, 1f)
    private val eCurtainIn = PathInterpolator(0.7f, 0f, 0.25f, 1f)
    private val eCurtainOut = PathInterpolator(0.7f, 0f, 0.3f, 1f)
    private val ePop = PathInterpolator(0.2f, 1.3f, 0.35f, 1f)
    private val eBlob = PathInterpolator(0.6f, 0f, 0.25f, 1f)
    private val eOut = PathInterpolator(0.5f, 0f, 0.3f, 1f)
    private val eZoom = PathInterpolator(0.6f, 0f, 0.9f, 0.6f)
    private val eWave = PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(n: Long) {
            if (!isAttachedToWindow) return
            t = (SystemClock.uptimeMillis() - start).toFloat()
            invalidate()
            if (t > ready + 900f) {
                onGone?.invoke()
                onGone = null
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** App ist bereit: Ausblenden starten, danach gone() aufrufen. Mindestens bis die Animation einmal drin ist. */
    fun finish(gone: () -> Unit) {
        if (ready != Float.MAX_VALUE) return
        onGone = gone
        ready = max(t, 1100f)
    }

    /** Sofort weg (z. B. Taste gedrueckt, waehrend noch eingeblendet wird): kurzes Ausblenden. */
    fun finishNow(gone: () -> Unit) {
        if (ready != Float.MAX_VALUE) return
        onGone = gone
        ready = t
    }

    private fun pr(s: Float, d: Float): Float = min(1f, max(0f, (t - s) / d))
    private fun mix(c: Int, f: Float): Int = Color.rgb(
        (5 + (Color.red(c) - 5) * f).toInt(), (6 + (Color.green(c) - 6) * f).toInt(), (10 + (Color.blue(c) - 10) * f).toInt())
    private fun dark(c: Int): Int = Color.rgb((Color.red(c) * 0.22f).toInt(), (Color.green(c) * 0.22f).toInt(), (Color.blue(c) * 0.22f).toInt())

    override fun onDraw(c: Canvas) {
        val k = width / 1920f
        c.save()
        c.scale(k, k)
        when (variant) {
            LaunchFx.WARP -> warp(c)
            LaunchFx.CURTAIN -> curtain(c)
            else -> liquid(c)
        }
        c.restore()
    }

    private fun logo(c: Canvas, b: Bitmap?, cx: Float, cy: Float, scale: Float, alpha: Float, w: Float = look.w, h: Float = look.h) {
        if (b == null || alpha <= 0f) return
        p.shader = null
        p.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        val hw = w * scale / 2
        val hh = h * scale / 2
        c.drawBitmap(b, null, RectF(cx - hw, cy - hh, cx + hw, cy + hh), p)
        p.alpha = 255
    }

    // ---------------------------------------------------------------- Warp
    private fun warp(c: Canvas) {
        val all = 1f - pr(ready + 150f, 500f)
        if (all <= 0f) return
        c.saveLayerAlpha(0f, 0f, 1920f, 1080f, (255 * all).toInt())
        val bgA = pr(0f, 450f)
        p.shader = null
        p.color = 0xFF020308.toInt()
        p.alpha = (255 * bgA).toInt()
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        val g = RadialGradient(0f, 0f, 630f, intArrayOf(mix(c0, 0.25f), 0x00020308), null, Shader.TileMode.CLAMP)
        val m = Matrix(); m.setScale(1f, 600f / 900f); m.postTranslate(960f, 540f); g.setLocalMatrix(m)
        p.shader = g
        p.alpha = (255 * bgA).toInt()
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        p.shader = null
        // Lichtstreifen aus der Mitte
        val sa = pr(0f, 300f)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 3f
        for (s in streaks) {
            if (t < s.d) continue
            val f = ((t - s.d) % s.dur) / s.dur
            val e = eIn.getInterpolation(f)
            val r0 = 30f + 1220f * e
            val len = s.w * (0.1f + 1.7f * e)
            val op = (if (f < 0.25f) f / 0.25f * s.o else s.o * (1f - (f - 0.25f) / 0.75f)) * sa
            val cs = cos(s.a)
            val sn = sin(s.a)
            p.color = c0
            p.alpha = (255 * op * 0.8f).toInt()
            c.drawLine(960f + cs * r0, 540f + sn * r0, 960f + cs * (r0 + len * 0.8f), 540f + sn * (r0 + len * 0.8f), p)
            p.color = Color.WHITE
            p.alpha = (255 * op).toInt()
            c.drawLine(960f + cs * (r0 + len * 0.8f), 540f + sn * (r0 + len * 0.8f), 960f + cs * (r0 + len), 540f + sn * (r0 + len), p)
        }
        // Logo mit pulsierendem Schein
        val li = eSoft.getInterpolation(pr(150f, 700f))
        var ls = 0.55f + 0.45f * li
        var la = li
        if (t > ready) {
            val z = eZoom.getInterpolation(pr(ready, 650f))
            ls *= 1f + 2.2f * z
            la *= 1f - z
        }
        val pulse = 0.5f - 0.5f * cos(2 * PI * t / 1600f).toFloat()
        p.shader = RadialGradient(960f, 540f, 280f * (0.92f + 0.16f * pulse), intArrayOf(Tone.alpha(c0, 0.45f), Tone.alpha(c0, 0f)), null, Shader.TileMode.CLAMP)
        p.alpha = (255 * la * (0.45f + 0.45f * pulse)).toInt()
        c.drawCircle(960f, 540f, 300f, p)
        p.shader = null
        logo(c, look.logo, 960f, 540f, ls, la)
        // Blitz beim Durchfliegen
        if (t > ready) {
            val f = pr(ready, 650f)
            val fa = if (f < 0.3f) f / 0.3f * 0.85f else 0.85f * (1f - (f - 0.3f) / 0.7f)
            p.shader = RadialGradient(960f, 540f, 1100f, intArrayOf(Color.WHITE, c0), floatArrayOf(0f, 0.6f), Shader.TileMode.CLAMP)
            p.alpha = (255 * fa).toInt()
            c.drawRect(0f, 0f, 1920f, 1080f, p)
            p.shader = null
        }
        c.restore()
    }

    // ---------------------------------------------------------------- Vorhang
    private fun curtain(c: Canvas) {
        val inE = eCurtainIn.getInterpolation(pr(0f, 650f))
        val outE = if (t > ready) eCurtainOut.getInterpolation(pr(ready, 750f)) else 0f
        val off = -961f * (1f - inE) - 980f * outE
        val dk = dark(c0)
        val col = mix(c0, 0.92f)
        // linke Haelfte
        p.shader = LinearGradient(off, 0f, off + 961f, 0f, dk, col, Shader.TileMode.CLAMP)
        p.alpha = 255
        c.drawRect(off, 0f, off + 961f, 1080f, p)
        // rechte Haelfte
        val ro = 959f - off
        p.shader = LinearGradient(ro, 0f, ro + 961f, 0f, col, dk, Shader.TileMode.CLAMP)
        c.drawRect(ro, 0f, ro + 961f, 1080f, p)
        p.shader = null
        p.color = 0x40FFFFFF
        c.drawRect(off + 959f, 0f, off + 961f, 1080f, p)
        c.drawRect(ro, 0f, ro + 2f, 1080f, p)
        // Lichtnaht
        val sf = pr(520f, 700f)
        if (sf > 0f && sf < 1f) {
            val sa = if (sf < 0.3f) sf / 0.3f else 1f - (sf - 0.3f) / 0.7f
            val sy = 0.2f + 0.8f * min(1f, sf / 0.3f)
            p.shader = LinearGradient(945f, 0f, 975f, 0f, intArrayOf(0x00FFFFFF, Color.WHITE, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            p.alpha = (255 * sa).toInt()
            c.drawRect(945f, 540f - 540f * sy, 975f, 540f + 540f * sy, p)
            p.shader = null
        }
        // Logo
        val pf = pr(520f, 600f)
        var s = if (pf < 0.7f) 0.3f + 0.78f * ePop.getInterpolation(pf / 0.7f) else 1.08f - 0.08f * ((pf - 0.7f) / 0.3f)
        var a = min(1f, pf / 0.7f)
        if (t > ready) {
            val o = PathInterpolator(0.42f, 0f, 1f, 1f).getInterpolation(pr(ready, 600f))
            s *= 1f + 0.5f * o
            a *= 1f - o
        }
        logo(c, look.white, 960f, 520f, s, a)
        // Ladebalken
        var ba = pr(700f, 400f)
        if (t > ready) ba *= 1f - pr(ready, 300f)
        if (ba > 0f) {
            p.color = 0x38FFFFFF
            p.alpha = (56 * ba).toInt()
            c.drawRoundRect(RectF(780f, 668f, 1140f, 672f), 2f, 2f, p)
            val ph = (t % 1500f) / 1500f
            val e = eWave.getInterpolation(if (ph < 0.5f) ph * 2 else 2 - ph * 2)
            val x = 780f - 140f + 480f * e
            c.save()
            c.clipRect(780f, 660f, 1140f, 680f)
            p.shader = LinearGradient(x, 0f, x + 180f, 0f, intArrayOf(0x00FFFFFF, Color.WHITE, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            p.alpha = (255 * ba).toInt()
            c.drawRoundRect(RectF(x, 668f, x + 180f, 672f), 2f, 2f, p)
            p.shader = null
            c.restore()
        }
    }

    // ---------------------------------------------------------------- Liquid Glass
    private fun liquid(c: Canvas) {
        var all = 1f
        var sc = 1f
        if (t > ready) {
            val o = eOut.getInterpolation(pr(ready, 650f))
            all = 1f - o
            sc = 1f + 0.05f * o
        }
        if (all <= 0f) return
        c.saveLayerAlpha(0f, 0f, 1920f, 1080f, (255 * all).toInt())
        c.scale(sc, sc, 960f, 540f)
        // Tropfen aus der Kachel
        val be = eBlob.getInterpolation(pr(0f, 1000f))
        if (t >= 0f) {
            val tcx = tile.centerX()
            val tcy = tile.centerY()
            val size0 = max(tile.width(), 120f)
            val cx = tcx + (960f - tcx) * be
            val cy = tcy + (540f - tcy) * be
            val s = 1f + be * (2600f / size0 - 1f)
            val wob = 0.08f * sin(PI * 2 * be).toFloat()
            val w = size0 * s * (1f + wob)
            val h = size0 * s * (1f - wob)
            val rad = (24f + (size0 / 2 - 24f) * be) * s
            p.shader = RadialGradient(cx - w * 0.1f, cy - h * 0.15f, max(w, h) * 0.75f, intArrayOf(mix(c0, 0.92f), dark(c0)), null, Shader.TileMode.CLAMP)
            p.alpha = 255
            c.drawRoundRect(RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), rad, rad, p)
            p.shader = null
        }
        // weiche Farbwolken
        val da = pr(150f, 600f)
        if (da > 0f) {
            fun cloud(x: Float, y: Float, r: Float, color: Int, a: Float, dx: Float, dy: Float, ds: Float, period: Float) {
                val f = 0.5f - 0.5f * cos(2 * PI * t / period).toFloat()
                val px = x + dx * f
                val py = y + dy * f
                val rr = r * (1f + ds * f)
                p.shader = RadialGradient(px, py, rr, intArrayOf(Tone.alpha(color, a * da), Tone.alpha(color, 0f)), null, Shader.TileMode.CLAMP)
                c.drawCircle(px, py, rr, p)
            }
            cloud(650f, 430f, 520f, c0, 0.55f, 180f, -90f, 0.15f, 7000f)
            cloud(1350f, 730f, 520f, mix(c0, 0.6f), 0.7f, -220f, 70f, -0.1f, 9000f)
            cloud(950f, 400f, 320f, Color.WHITE, 0.22f, 90f, 120f, 0f, 6000f)
            p.shader = null
        }
        // Glaskarte mit Kometenring
        val jf = pr(650f, 900f)
        if (jf > 0f) {
            val (sx, sy) = jelly(jf)
            val ga = min(1f, jf / 0.35f)
            c.save()
            c.scale(sx, sy, 960f, 540f)
            val card = RectF(680f, 380f, 1240f, 700f)
            p.shader = LinearGradient(card.left, card.top, card.right, card.bottom, intArrayOf(0x33FFFFFF, 0x0FFFFFFF, 0x1AFFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            p.alpha = (255 * ga).toInt()
            c.drawRoundRect(card, 48f, 48f, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f
            p.color = 0x61FFFFFF
            p.alpha = (97 * ga).toInt()
            c.drawRoundRect(card, 48f, 48f, p)
            // Kometenring: Lichtpunkt laeuft um den Rand
            val rot = (t / 1600f) * 360f
            val sweep = SweepGradient(960f, 540f, intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xD9FFFFFF.toInt(), Color.WHITE, 0x00FFFFFF), floatArrayOf(0f, 0.53f, 0.875f, 0.98f, 1f))
            val sm = Matrix(); sm.setRotate(rot, 960f, 540f); sweep.setLocalMatrix(sm)
            p.shader = sweep
            p.strokeWidth = 14f
            p.alpha = (90 * ga).toInt()
            c.drawRoundRect(card, 48f, 48f, p)
            p.strokeWidth = 4f
            p.alpha = (255 * ga).toInt()
            c.drawRoundRect(card, 48f, 48f, p)
            p.shader = null
            p.style = Paint.Style.FILL
            // Glanz zieht einmal ueber die Karte
            val sh = pr(700f, 1300f)
            if (sh > 0f && sh < 1f) {
                c.save()
                val path = android.graphics.Path()
                path.addRoundRect(card, 48f, 48f, android.graphics.Path.Direction.CW)
                c.clipPath(path)
                val x = card.left - 280f + (card.width() + 560f) * PathInterpolator(0.42f, 0f, 0.58f, 1f).getInterpolation(sh)
                p.shader = LinearGradient(x, 0f, x + 200f, 0f, intArrayOf(0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawRect(x, card.top, x + 200f, card.bottom, p)
                p.shader = null
                c.restore()
            }
            logo(c, look.white, 960f, 540f, 0.78f * (if (look.kind.isEmpty()) 0.8f else 1f), ga)
            c.restore()
        }
        c.restore()
    }

    /** Wabbeln wie Gelee: klein, breit, schmal, etwas breit, normal. */
    private fun jelly(f: Float): Pair<Float, Float> {
        val keys = floatArrayOf(0f, 0.35f, 0.55f, 0.75f, 1f)
        val xs = floatArrayOf(0.55f, 1.08f, 0.96f, 1.02f, 1f)
        val ys = floatArrayOf(0.55f, 0.94f, 1.04f, 0.99f, 1f)
        var i = 0
        while (i < keys.size - 2 && f > keys[i + 1]) i++
        val lf = ((f - keys[i]) / (keys[i + 1] - keys[i])).coerceIn(0f, 1f)
        val e = eSoft.getInterpolation(lf)
        return (xs[i] + (xs[i + 1] - xs[i]) * e) to (ys[i] + (ys[i + 1] - ys[i]) * e)
    }
}
