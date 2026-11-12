package de.jugie.firelauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.dreams.DreamService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Stellt den Bildschirmschoner von Fire OS auf Liquid TV um und auf Wunsch wieder zurueck.
 * Die Originalwerte werden beim ersten Umstellen gemerkt; "Fire TV Standard" setzt sie zurueck.
 */
object Saver {
    const val KINO = 0
    const val WELLEN = 1
    const val SYSTEM = 2
    val NAMES = arrayOf("Filme & Serien", "Wellen & Uhr", "Fire TV Standard")
    val MINUTES = intArrayOf(5, 10, 15)

    private const val AMAZON = "com.amazon.bueller.photos/.daydream.ScreenSaverService"
    private val SECURE = arrayOf(
        "screensaver_components", "screensaver_default_component", "screensaver_enabled",
        "amazon_ambient_enabled", "contextual_screen_off_timeout"
    )
    private const val TIMEOUT = "screen_off_timeout"

    fun component(c: Context): String = ComponentName(c, ScreensaverDream::class.java).flattenToShortString()

    /** true, wenn das System gerade unseren Bildschirmschoner verwendet. */
    fun active(c: Context): Boolean =
        try {
            (Settings.Secure.getString(c.contentResolver, "screensaver_components") ?: "").contains(c.packageName)
        } catch (e: Exception) {
            false
        }

    /** Passt die Systemwerte an die Auswahl an. Schreibt nur, was sich wirklich aendert. */
    fun apply(c: Context) {
        if (!Setup.canWrite(c)) return
        try {
            if (Prefs.saver(c) == SYSTEM) {
                restore(c)
                return
            }
            val cr = c.contentResolver
            if (!Prefs.flag(c, "saver_saved")) {
                for (key in SECURE) Prefs.setOrig(c, key, Settings.Secure.getString(cr, key))
                Prefs.setOrig(c, TIMEOUT, Settings.System.getString(cr, TIMEOUT))
                Prefs.setFlag(c, "saver_saved")
            }
            val me = component(c)
            val ms = (Prefs.saverMinutes(c) * 60000).toString()
            secure(c, "screensaver_components", me)
            secure(c, "screensaver_default_component", me)
            secure(c, "screensaver_enabled", "1")
            // Fire OS 8 zeigt sonst sein eigenes "Ambient Experience" statt unseres Bildschirmschoners
            secure(c, "amazon_ambient_enabled", "0")
            secure(c, "contextual_screen_off_timeout", ms)
            system(c, TIMEOUT, ms)
        } catch (e: Exception) {
        }
    }

    private fun restore(c: Context) {
        if (!Prefs.flag(c, "saver_saved")) return
        for (key in SECURE) {
            var v = Prefs.orig(c, key)
            if (v == null && key == "screensaver_components") v = AMAZON
            secure(c, key, v)
        }
        val t = Prefs.orig(c, TIMEOUT)
        if (t != null) system(c, TIMEOUT, t)
        Prefs.clearFlag(c, "saver_saved")
    }

    private fun secure(c: Context, key: String, v: String?) {
        try {
            if (Settings.Secure.getString(c.contentResolver, key) != v) Settings.Secure.putString(c.contentResolver, key, v)
        } catch (e: Exception) {
        }
    }

    private fun system(c: Context, key: String, v: String) {
        try {
            if (Settings.System.getString(c.contentResolver, key) != v) Settings.System.putString(c.contentResolver, key, v)
        } catch (e: Exception) {
        }
    }
}

/** Der eigentliche Bildschirmschoner, den Fire OS nach der eingestellten Zeit startet. Jede Taste beendet ihn. */
class ScreensaverDream : DreamService() {
    private var view: ScreensaverView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // interaktiv, damit die Spotify-Steuerung geht; jede andere Taste beendet den Schoner
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val v = ScreensaverView(this)
        view = v
        setContentView(v)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (view?.media(event) == true) return true
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        view?.stop()
        view = null
        super.onDetachedFromWindow()
    }
}

/** Zum Ausprobieren aus den Einstellungen: zeigt denselben Bildschirmschoner, jede Taste schliesst ihn. */
class ScreensaverActivity : Activity() {
    private var view: ScreensaverView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = ScreensaverView(this)
        view = v
        setContentView(v)
    }

    override fun onResume() {
        super.onResume()
        view?.start()
    }

    override fun onPause() {
        view?.stop()
        super.onPause()
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (view?.media(event) == true) return true
        // erst beim Loslassen schliessen, damit die Taste nicht im Fenster dahinter ankommt
        if (event.action == KeyEvent.ACTION_UP) {
            finish()
            overridePendingTransition(0, android.R.anim.fade_out)
        }
        return true
    }
}

/** Bewegungen, die genau so auch in der Vorschau laufen (gleiche Wegpunkte, Zeiten und Kurven). */
object Motion {
    /** CSS "ease-in-out" */
    private val inOut = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    /** Mittelpunkte der beiden Farbflecken (in 1920er-Koordinaten), je 12 s pro Abschnitt. */
    val GLOW_A = arrayOf(floatArrayOf(460f, 780f), floatArrayOf(1460f, 300f), floatArrayOf(1580f, 800f), floatArrayOf(380f, 300f))
    val GLOW_B = arrayOf(floatArrayOf(1480f, 320f), floatArrayOf(420f, 820f), floatArrayOf(360f, 280f), floatArrayOf(1560f, 780f))

    /** Langsames Wandern der Uhr gegen Einbrennen, 36 s pro Abschnitt. */
    val DRIFT = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-70f, -30f), floatArrayOf(60f, -40f), floatArrayOf(70f, 30f), floatArrayOf(-60f, 40f))

    /** Farben der Apps, hin und zurueck, damit nie Gegenfarben ineinander laufen; 7 s pro Farbe. */
    private val COLORS = intArrayOf(
        0xFFFF0000.toInt(), 0xFF9246FF.toInt(), 0xFF2896D3.toInt(), 0xFF31BADD.toInt(), 0xFF02AAB2.toInt(),
        0xFF1ED860.toInt(), 0xFF02AAB2.toInt(), 0xFF31BADD.toInt(), 0xFF2896D3.toInt(), 0xFF9246FF.toInt()
    )
    private const val COLOR_MS = 7000L

    private fun wrap(t: Long, m: Long): Long = ((t % m) + m) % m

    fun path(pts: Array<FloatArray>, seg: Long, t: Long, out: FloatArray) {
        val n = pts.size
        val tt = wrap(t, seg * n)
        val i = (tt / seg).toInt()
        val f = inOut.getInterpolation((tt % seg).toFloat() / seg)
        val a = pts[i]
        val b = pts[(i + 1) % n]
        out[0] = a[0] + (b[0] - a[0]) * f
        out[1] = a[1] + (b[1] - a[1]) * f
    }

    fun color(t: Long): Int {
        val n = COLORS.size
        val tt = wrap(t, COLOR_MS * n)
        val i = (tt / COLOR_MS).toInt()
        return mix(COLORS[i], COLORS[(i + 1) % n], (tt % COLOR_MS).toFloat() / COLOR_MS)
    }

    /** Farbmischung wie im Browser (direkt in sRGB). */
    fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
    )
}

/** Wellenbild mit zwei wandernden Farbflecken in den App-Farben. Gezeichnet mit drei Rechtecken. */
class AuroraLayer(ctx: Context) : View(ctx) {
    private val bmp: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.bg_waves, BitmapFactory.Options().apply { inScaled = false })
    } catch (e: Throwable) {
        null
    }
    private val base = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var shader: BitmapShader? = null
    private val dst = RectF()
    private val gm = Matrix()
    private val cm = ColorMatrix()
    private val pos = FloatArray(2)
    private var k = 1f
    var t = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        k = w / 1920f
        val b = bmp ?: return
        val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setScale(w / b.width.toFloat(), h / b.height.toFloat())
        s.setLocalMatrix(m)
        shader = s
    }

    override fun onDraw(c: Canvas) {
        val b = bmp
        if (b == null) {
            c.drawColor(Color.BLACK)
            return
        }
        c.drawBitmap(b, null, dst, base)
        Motion.path(Motion.GLOW_A, 12000L, t, pos)
        spot(c, pos[0], pos[1], 1050f, 850f, Motion.color(t), 1f)
        Motion.path(Motion.GLOW_B, 12000L, t, pos)
        spot(c, pos[0], pos[1], 820f, 660f, Motion.color(t + 14000L), 0.8f)
    }

    private fun spot(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int, alpha: Float) {
        val s = shader ?: return
        val rg = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFF000000.toInt(), 0x9E000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        gm.reset()
        gm.setScale(rx * k, ry * k)
        gm.postTranslate(x * k, y * k)
        rg.setLocalMatrix(gm)
        glow.shader = ComposeShader(s, rg, PorterDuff.Mode.DST_IN)
        val g = 2.6f
        cm.set(
            floatArrayOf(
                Color.red(color) / 255f * g, 0f, 0f, 0f, 0f,
                0f, Color.green(color) / 255f * g, 0f, 0f, 0f,
                0f, 0f, Color.blue(color) / 255f * g, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        glow.colorFilter = ColorMatrixColorFilter(cm)
        glow.alpha = (255 * alpha).toInt()
        c.drawRect(dst, glow)
    }
}

/** Szenenbilder: zoomen langsam heran und blenden ueber das vorige Bild. */
class KinoLayer(ctx: Context) : View(ctx) {
    companion object {
        const val SLIDE = 12000L
        const val FADE = 1600L
    }

    private class Slide(val bmp: Bitmap, val start: Long, val pivotX: Float)

    private var cur: Slide? = null
    private var prev: Slide? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    /** CSS "ease" fuer die Ueberblendung */
    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    var now = 0L

    fun push(b: Bitmap, start: Long, pivotX: Float) {
        prev = cur
        cur = Slide(b, start, pivotX)
    }

    fun clear() {
        prev = null
        cur = null
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val s = cur ?: return
        val lin = ((now - s.start) / FADE.toFloat()).coerceIn(0f, 1f)
        val p = prev
        if (p != null) {
            if (lin >= 1f) prev = null else draw(c, p, 255)
        }
        draw(c, s, (255 * ease.getInterpolation(lin)).toInt())
    }

    private fun draw(c: Canvas, s: Slide, alpha: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = s.bmp.width.toFloat()
        val bh = s.bmp.height.toFloat()
        val cover = Math.max(w / bw, h / bh)
        val p = ((now - s.start) / (SLIDE + FADE).toFloat()).coerceIn(0f, 1f)
        val sc = 1f + 0.1f * p
        m.setScale(cover, cover)
        m.postTranslate((w - bw * cover) / 2f, (h - bh * cover) / 2f)
        m.postScale(sc, sc, w * s.pivotX, h * 0.4f)
        paint.alpha = alpha
        c.drawBitmap(s.bmp, m, paint)
    }
}

/** Abdunklung fuer die Schrift und ein weicher Schein in der Farbe des Anbieters unten links. */
class ShadeView(ctx: Context) : View(ctx) {
    private val left = Paint()
    private val bottom = Paint()
    private val top = Paint()
    private val glowPaint = Paint()
    private val gm = Matrix()
    private var glow = 0xFFE50000.toInt()
    private var anim: ValueAnimator? = null
    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        left.shader = LinearGradient(
            0f, 0f, fw, 0f, intArrayOf(0xD1000000.toInt(), 0x73000000, 0x00000000),
            floatArrayOf(0f, 0.38f, 0.66f), Shader.TileMode.CLAMP
        )
        bottom.shader = LinearGradient(
            0f, fh, 0f, 0f, intArrayOf(0xE0000000.toInt(), 0x66000000, 0x00000000),
            floatArrayOf(0f, 0.32f, 0.58f), Shader.TileMode.CLAMP
        )
        top.shader = LinearGradient(
            0f, 0f, 0f, fh, intArrayOf(0x8C000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0.26f), Shader.TileMode.CLAMP
        )
        rebuildGlow()
    }

    fun setGlow(c: Int, animate: Boolean) {
        anim?.cancel()
        if (!animate) {
            glow = c
            rebuildGlow()
            return
        }
        val from = glow
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1200
        a.interpolator = ease
        a.addUpdateListener {
            glow = Motion.mix(from, c, it.animatedValue as Float)
            rebuildGlow()
        }
        a.start()
        anim = a
    }

    private fun rebuildGlow() {
        if (width <= 0) return
        val k = width / 1920f
        val rgb = glow and 0x00FFFFFF
        val rg = RadialGradient(0f, 0f, 1f, intArrayOf(0x4D000000 or rgb, rgb), null, Shader.TileMode.CLAMP)
        gm.reset()
        gm.setScale(900f * k, 520f * k)
        gm.postTranslate(380f * k, 1000f * k)
        rg.setLocalMatrix(gm)
        glowPaint.shader = rg
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, left)
        c.drawRect(0f, 0f, w, h, bottom)
        c.drawRect(0f, 0f, w, h, top)
        c.drawRect(0f, 0f, w, h, glowPaint)
    }
}

/**
 * Der Bildschirmschoner selbst. "Filme & Serien": Szenenbilder neuer Titel der Streaming-Apps.
 * "Wellen & Uhr": Wellenbild mit wandernden Farben und grosser Uhr. Ohne Internet immer Wellen.
 */
class ScreensaverView(ctx: Context) : FrameLayout(ctx) {
    private companion object {
        const val NONE = 0
        const val KINO = 1
        const val WELLEN = 2
    }

    private val k = ctx.resources.displayMetrics.widthPixels / 1920f
    private fun px(v: Float): Int = (v * k).toInt()
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val handler = Handler(Looper.getMainLooper())
    private val shadow = 0x59000000

    private var running = false
    private var mode = NONE
    private var t0 = 0L
    private val pos = FloatArray(2)

    // Wellen & Uhr
    private val wellen = FrameLayout(ctx)
    private val aurora = AuroraLayer(ctx)
    private val block = LinearLayout(ctx)
    private val weatherRow = LinearLayout(ctx)
    private val wIcon = WeatherIcon(ctx)
    private val wTemp = Ui.text(ctx, "", 32f, k, Color.WHITE, Ui.medium)
    private val wCond = Ui.text(ctx, "", 32f, k, 0xBFFFFFFF.toInt())
    private val bigFlip = FlipClock(ctx, k, 170f, 240f, 180f, 14f, 22f, 16f, R.drawable.flip_big, Prefs.h24(ctx))
    private val smallFlip = FlipClock(ctx, k, 78f, 108f, 80f, 8f, 10f, 8f, R.drawable.flip_small, Prefs.h24(ctx))
    private val weatherRow2 = LinearLayout(ctx)
    private val wIcon2 = WeatherIcon(ctx)
    private val wTemp2 = Ui.text(ctx, "", 26f, k, Color.WHITE, Ui.medium)
    private val wCond2 = Ui.text(ctx, "", 26f, k, 0xBFFFFFFF.toInt())

    private val npCard = NowCard(ctx, k, saver = true)
    private val npListen: (Spotify.Track?) -> Unit = { t ->
        npCard.set(t)
        val want = t != null
        if (want != (npCard.visibility == View.VISIBLE)) {
            if (want) {
                npCard.visibility = View.VISIBLE
                npCard.alpha = 0f
                npCard.animate().alpha(1f).setDuration(600).start()
            } else npCard.visibility = View.GONE
        }
    }

    /** ◀ voriger, OK Pause, ▶ naechster Titel (und die Mediatasten), solange Musik laeuft. */
    fun media(e: KeyEvent): Boolean {
        if (NowPlaying.track == null) return false
        val a = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "toggle"
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) NowPlaying.control(context, a)
        return true
    }

    // Filme & Serien
    private val kinoGroup = FrameLayout(ctx)
    private val kino = KinoLayer(ctx)
    private val shade = ShadeView(ctx)
    private val info = LinearLayout(ctx)
    private val logo = ImageView(ctx)
    private val title = Ui.text(ctx, "", 84f, k, Color.WHITE, Ui.medium)
    private val meta = Ui.text(ctx, "", 28f, k, 0xC7FFFFFF.toInt())
    private val desc = Ui.text(ctx, "", 26f, k, 0x9EFFFFFF.toInt())
    private val progFill = View(ctx)
    private val saverLabel = Ui.text(ctx, "NEU UND BELIEBT", 22f, k, 0xCCFFFFFF.toInt(), Ui.medium)
    private val saverDiv = View(ctx)

    private var items: List<Content.SaverItem> = emptyList()
    private var step = -1
    private var curIdx = -1
    private var slideAt = 0L
    private var nextIdx = -1
    private var nextBmp: Bitmap? = null
    private var fetching = false
    private var lastTry = 0L

    init {
        setBackgroundColor(Color.BLACK)
        buildWellen()
        buildKino()
    }

    private fun timeFmt(): String = if (Prefs.h24(context)) "HH:mm" else "h:mm"

    private fun clock(fmt: String, size: Float, color: Int, face: Typeface?): TextClock {
        val t = TextClock(context)
        t.format24Hour = fmt
        t.format12Hour = fmt
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k)
        t.setTextColor(color)
        if (face != null) t.typeface = face
        t.includeFontPadding = false
        t.setShadowLayer(24f * k, 0f, 2f * k, shadow)
        return t
    }

    private fun lp(top: Float = 0f, left: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(wrap, wrap).apply {
            topMargin = px(top)
            leftMargin = px(left)
        }

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 2f * k
    }

    private fun buildWellen() {
        wellen.addView(aurora, LayoutParams(match, match))

        block.orientation = LinearLayout.VERTICAL
        block.gravity = Gravity.CENTER_HORIZONTAL
        // Klapp-Uhr aus Glaskacheln
        block.addView(bigFlip)
        block.addView(clock("EEEE, d. MMMM", 40f, 0xCCFFFFFF.toInt(), null), lp(top = 22f))

        weatherRow.orientation = LinearLayout.HORIZONTAL
        weatherRow.gravity = Gravity.CENTER_VERTICAL
        weatherRow.addView(wIcon, LinearLayout.LayoutParams(px(56f), px(56f)))
        val dim = 0xBFFFFFFF.toInt()
        val parts = listOf(wTemp, Ui.text(context, "·", 32f, k, dim), wCond, Ui.text(context, "·", 32f, k, dim), Ui.text(context, "Grabow", 32f, k, dim))
        for (p in parts) {
            p.setShadowLayer(24f * k, 0f, 2f * k, shadow)
            weatherRow.addView(p, lp(left = 16f))
        }
        weatherRow.visibility = View.INVISIBLE
        block.addView(weatherRow, lp(top = 26f))
        wellen.addView(block, LayoutParams(wrap, wrap, Gravity.CENTER))

        val brand = Ui.text(context, "LIQUID TV", 20f, k, 0x52FFFFFF, Ui.medium)
        brand.letterSpacing = 0.3f
        wellen.addView(brand, LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(54f) })

        wellen.visibility = View.GONE
        addView(wellen, LayoutParams(match, match))
    }

    private fun buildKino() {
        kinoGroup.addView(kino, LayoutParams(match, match))
        kinoGroup.addView(shade, LayoutParams(match, match))

        val topRight = LinearLayout(context)
        topRight.orientation = LinearLayout.VERTICAL
        topRight.gravity = Gravity.END
        topRight.addView(smallFlip)
        topRight.addView(clock("EEEE, d. MMMM", 30f, 0xCCFFFFFF.toInt(), null), lp(top = 12f))
        weatherRow2.orientation = LinearLayout.HORIZONTAL
        weatherRow2.gravity = Gravity.CENTER_VERTICAL
        weatherRow2.addView(wIcon2, LinearLayout.LayoutParams(px(42f), px(42f)))
        weatherRow2.addView(wTemp2, lp(left = 12f))
        weatherRow2.addView(wCond2, lp(left = 12f))
        for (t in listOf(wTemp2, wCond2)) t.setShadowLayer(24f * k, 0f, 2f * k, shadow)
        weatherRow2.visibility = View.INVISIBLE
        topRight.addView(weatherRow2, lp(top = 12f))
        kinoGroup.addView(topRight, LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
            topMargin = px(84f)
            rightMargin = px(112f)
        })

        info.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        head.addView(logo, LinearLayout.LayoutParams(wrap, px(44f)))
        val div = saverDiv
        div.setBackgroundColor(0x4DFFFFFF)
        head.addView(div, LinearLayout.LayoutParams(Math.max(1, px(2f)), px(30f)).apply {
            leftMargin = px(20f)
            rightMargin = px(20f)
        })
        saverLabel.letterSpacing = 3f / 22f
        head.addView(saverLabel)
        info.addView(head)

        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        if (Build.VERSION.SDK_INT >= 28) title.lineHeight = px(88f)
        info.addView(title, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(26f) })
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        info.addView(meta, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(20f) })
        desc.maxLines = 2
        desc.ellipsize = TextUtils.TruncateAt.END
        if (Build.VERSION.SDK_INT >= 28) desc.lineHeight = px(36f)
        info.addView(desc, LinearLayout.LayoutParams(px(920f), wrap).apply { topMargin = px(18f) })
        for (v in listOf(title, meta, desc)) v.setShadowLayer(24f * k, 0f, 2f * k, shadow)

        val track = FrameLayout(context)
        track.background = rounded(0x33FFFFFF)
        track.clipToOutline = true
        progFill.background = rounded(0xD9FFFFFF.toInt())
        progFill.pivotX = 0f
        progFill.scaleX = 0f
        track.addView(progFill, LayoutParams(px(240f), match))
        info.addView(track, LinearLayout.LayoutParams(px(240f), Math.max(2, px(4f))).apply { topMargin = px(34f) })

        kinoGroup.addView(info, LayoutParams(px(1100f), wrap, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = px(112f)
            bottomMargin = px(112f)
        })

        kinoGroup.visibility = View.GONE
        addView(kinoGroup, LayoutParams(match, match))

        // Spotify oben wie die HSV-Nachricht, auf einer Hoehe mit der Uhr
        npCard.visibility = View.GONE
        addView(npCard, LayoutParams(px(740f), px(204f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(36f) })
    }

    fun start() {
        if (running) return
        running = true
        NowPlaying.listen(npListen)
        NowPlaying.start(context)
        npListen(NowPlaying.track)
        mode = NONE
        t0 = SystemClock.uptimeMillis()
        bigFlip.start()
        smallFlip.start()
        wIcon.live = true
        wIcon2.live = true
        Weather.load { updateWeather() }
        if (Prefs.saver(context) == Saver.WELLEN) {
            showWellen()
        } else {
            Content.loadSaver { list ->
                if (!running || mode != NONE) return@loadSaver
                if (list.isEmpty()) {
                    showWellen()
                    return@loadSaver
                }
                items = list
                fetch(0) { i, b ->
                    if (running && mode == NONE) {
                        if (b == null) {
                            showWellen()
                        } else {
                            showKino()
                            show(i, b)
                            prefetch()
                        }
                    }
                }
            }
            // ohne Internet nicht lange schwarz bleiben
            handler.postDelayed({ if (running && mode == NONE) showWellen() }, 8000)
        }
        post(tick)
    }

    fun stop() {
        if (running) {
            NowPlaying.unlisten(npListen)
            NowPlaying.stop()
        }
        running = false
        bigFlip.stop()
        smallFlip.stop()
        wIcon.live = false
        wIcon2.live = false
        removeCallbacks(tick)
        handler.removeCallbacksAndMessages(null)
        kino.clear()
        nextBmp = null
        fetching = false
        mode = NONE
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            when (mode) {
                WELLEN -> {
                    val t = now - t0
                    aurora.t = t
                    aurora.invalidate()
                    Motion.path(Motion.DRIFT, 36000L, t, pos)
                    block.translationX = pos[0] * k
                    block.translationY = pos[1] * k
                    // ruhige Bewegung, 30 Bilder pro Sekunde reichen und schonen den Stick
                    postDelayed(this, 33)
                }
                KINO -> {
                    kino.now = now
                    kino.invalidate()
                    if (now - slideAt >= KinoLayer.SLIDE) {
                        val b = nextBmp
                        if (b != null) {
                            nextBmp = null
                            show(nextIdx, b)
                            prefetch()
                        } else if (!fetching && now - lastTry > 20000L) {
                            prefetch()
                        }
                    }
                    postOnAnimation(this)
                }
                else -> postDelayed(this, 100)
            }
        }
    }

    private fun showWellen() {
        mode = WELLEN
        (npCard.layoutParams as LayoutParams).topMargin = px(-20f)
        npCard.requestLayout()
        handler.removeCallbacksAndMessages(null)
        kinoGroup.visibility = View.GONE
        wellen.visibility = View.VISIBLE
        wellen.alpha = 0f
        wellen.animate().alpha(1f).setDuration(1200).start()
    }

    private fun showKino() {
        mode = KINO
        (npCard.layoutParams as LayoutParams).topMargin = px(36f)
        npCard.requestLayout()
        handler.removeCallbacksAndMessages(null)
        wellen.visibility = View.GONE
        kinoGroup.visibility = View.VISIBLE
        kinoGroup.alpha = 0f
        kinoGroup.animate().alpha(1f).setDuration(1200).start()
    }

    /** Grosse Fassung des Bildes zuerst, die normale als Ersatz. */
    private fun big(urls: List<String>): List<String> =
        urls.flatMap { u -> if (u.contains("/w1280/")) listOf(u.replace("/w1280/", "/original/"), u) else listOf(u) }

    private fun fetch(from: Int, cb: (Int, Bitmap?) -> Unit) {
        fetchTry(from, Math.min(4, items.size), cb)
    }

    private fun fetchTry(i: Int, left: Int, cb: (Int, Bitmap?) -> Unit) {
        if (left <= 0 || items.isEmpty()) {
            cb(-1, null)
            return
        }
        val j = ((i % items.size) + items.size) % items.size
        Images.loadBig(big(items[j].item.images), 1920) { b ->
            if (!running) return@loadBig
            if (b != null) cb(j, b) else fetchTry(j + 1, left - 1, cb)
        }
    }

    private fun prefetch() {
        if (fetching || items.isEmpty()) return
        fetching = true
        lastTry = SystemClock.uptimeMillis()
        fetch(curIdx + 1) { i, b ->
            fetching = false
            if (b != null) {
                nextIdx = i
                nextBmp = b
            }
        }
    }

    private fun show(i: Int, b: Bitmap) {
        step++
        curIdx = i
        slideAt = SystemClock.uptimeMillis()
        kino.now = slideAt
        kino.push(b, slideAt, if (step % 2 == 0) 0.3f else 0.7f)
        val s = items[i]
        val kino = s.kind == "kino"
        logo.visibility = if (kino) View.GONE else View.VISIBLE
        saverDiv.visibility = logo.visibility
        if (!kino) {
            logo.setImageResource(logoFor(s.kind))
            val llp = logo.layoutParams
            llp.height = px(if (s.kind == "wow") 30f else 44f)
            logo.layoutParams = llp
        }
        // Rubrik ohne den Anbieternamen (der steht schon als Logo davor)
        val rub = s.item.label.replace(Regex(" bei .*$"), "").trim()
        saverLabel.text = (if (rub.isBlank()) "Neu und beliebt" else rub).uppercase()
        title.text = s.item.title
        meta.text = s.item.meta.replace("  ·  ", " · ")
        desc.text = s.item.desc
        desc.visibility = if (s.item.desc.isBlank()) View.GONE else View.VISIBLE

        info.animate().cancel()
        info.alpha = 0f
        info.translationY = 24f * k
        info.animate().alpha(1f).translationY(0f).setStartDelay(300).setDuration(700).setInterpolator(Ui.smooth).start()
        progFill.animate().cancel()
        progFill.scaleX = 0f
        progFill.animate().scaleX(1f).setStartDelay(0).setDuration(KinoLayer.SLIDE).setInterpolator(LinearInterpolator()).start()
        shade.setGlow(accentFor(s.kind), step > 0)
    }

    private fun logoFor(kind: String): Int = when (kind) {
        "netflix" -> R.drawable.logo_netflix
        "prime" -> R.drawable.logo_prime
        "wow" -> R.drawable.logo_wow
        else -> R.drawable.logo_disney
    }

    private fun accentFor(kind: String): Int = when (kind) {
        "kino" -> 0xFFE8B450.toInt()
        "netflix" -> 0xFFE50000.toInt()
        "prime" -> 0xFF2896D3.toInt()
        "wow" -> 0xFF02AAB2.toInt()
        else -> 0xFF31BADD.toInt()
    }

    private fun updateWeather() {
        val t = Weather.temp ?: return
        val kind = WeatherIcon.kindFor(Weather.code, Weather.day)
        wIcon.kind = kind
        wIcon2.kind = kind
        wTemp.text = "$t°"
        wTemp2.text = "$t°"
        wCond.text = Weather.text(Weather.code)
        wCond2.text = Weather.text(Weather.code)
        weatherRow.visibility = View.VISIBLE
        weatherRow2.visibility = View.VISIBLE
    }
}
