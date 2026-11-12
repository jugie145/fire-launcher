package de.jugie.firelauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Naechste HSV-Spiele (aus content.json, Quelle OpenLigaDB) und wann die Nachricht erscheint. */
object Hsv {
    class Match(val kick: Long, val opp: String, val id: Long = 0, val home: Boolean = true)
    class Info(val title: String, val opp: String, val line3: String, val glow: Int, val live: Boolean = false, val match: Match? = null)

    const val BLUE = 0
    const val VIOLET = 1
    const val RED = 2

    private const val MATCH_LEN = 2 * 60 * 60 * 1000L

    private fun day(t: Long): Long {
        val tz = TimeZone.getDefault()
        return (t + tz.getOffset(t)) / 86400000L
    }

    /**
     * Freitagsspiel: nur Freitag. Samstag: Freitag und Samstag. Sonntag: Freitag bis Sonntag.
     * Alle anderen Tage (englische Woche): nur am Spieltag. Bis etwa zum Abpfiff.
     */
    fun info(list: List<Match>, now: Long): Info? {
        val m = list.filter { now < it.kick + MATCH_LEN }.minByOrNull { it.kick } ?: return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = m.kick
        val before = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> 1
            Calendar.SUNDAY -> 2
            else -> 0
        }
        val kd = day(m.kick)
        val today = day(now)
        if (today < kd - before || today > kd) return null
        val diff = kd - today
        val title: String
        val glow: Int
        when (diff) {
            0L -> { title = "HSV SPIELT HEUTE!"; glow = BLUE }
            1L -> { title = "HSV SPIELT MORGEN!"; glow = VIOLET }
            else -> {
                val wd = SimpleDateFormat("EEEE", Locale.GERMANY).format(Date(m.kick)).uppercase(Locale.GERMANY)
                title = "HSV SPIELT AM $wd!"
                glow = RED
            }
        }
        val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(m.kick))
        val left = (m.kick - now) / 60000L
        val line3 = when {
            now >= m.kick -> "Läuft gerade · Anstoß war $time Uhr"
            left < 60 -> "Anstoß in " + Math.max(1, left) + " Min. · $time Uhr"
            left < 120 -> "Anstoß in 1 Std. " + (left - 60) + " Min. · $time Uhr"
            else -> "Anstoß: $time Uhr"
        }
        return Info(title, m.opp, line3, glow, now >= m.kick, m)
    }
}

/**
 * HSV-Karte (640 x 104) wie im Entwurf: neutrales Glas, ein Lichtpunkt in der Farbe der Nachricht
 * laeuft um den Rand. Gleitet oben herein, wird zum Verschwinden kurz gestaucht.
 * Waehrend des Spiels: Live-Stand, bei jedem HSV-Tor eine Tor-Animation.
 */
class HsvNoteView(ctx: Context, private val k: Float) : FrameLayout(ctx) {
    private val glass = ImageView(ctx)
    private var comet: CometRing? = null
    private var cometColor = 0
    private val normal = LinearLayout(ctx)
    private val live = LinearLayout(ctx)
    private val title: TextView
    private val opp: TextView
    private val line3: TextView
    private val phase: TextView
    private val score: TextView
    private val oppLive: TextView
    private val last: TextView
    private val goalFlash = View(ctx)
    private val goalText = LinearLayout(ctx)
    private val goalScore: TextView
    private val body = FrameLayout(ctx)
    private var anim: ValueAnimator? = null
    var showing = false
        private set
    private val springIn = PathInterpolator(0.2f, 1.25f, 0.35f, 1f)
    private val easeOut = PathInterpolator(0f, 0f, 0.58f, 1f)
    private val easeIn = PathInterpolator(0.45f, 0f, 0.7f, 0.2f)

    private fun px(v: Int): Int = (v * k).toInt()

    init {
        clipChildren = false
        clipToPadding = false
        body.clipChildren = false
        addView(body, LayoutParams(px(740), px(204)))
        glass.scaleType = ImageView.ScaleType.FIT_XY
        glass.setImageResource(R.drawable.glass_note_neutral)
        body.addView(glass, LayoutParams(px(740), px(204)))
        val logo = ImageView(ctx)
        logo.setImageResource(R.drawable.logo_hsv)
        logo.scaleType = ImageView.ScaleType.FIT_XY
        body.addView(logo, LayoutParams(px(72), px(54)).apply { leftMargin = px(50 + 20); topMargin = px(50 + 25) })
        val bold = Typeface.create("sans-serif", Typeface.BOLD)

        normal.orientation = LinearLayout.VERTICAL
        normal.gravity = Gravity.CENTER_VERTICAL
        title = Ui.text(ctx, "", 26f, k, Color.WHITE, bold)
        opp = Ui.text(ctx, "", 19f, k, 0xE0FFFFFF.toInt())
        line3 = Ui.text(ctx, "", 19f, k, 0xE0FFFFFF.toInt())
        for (t in listOf(title, opp, line3)) {
            t.maxLines = 1
            normal.addView(t, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2) })
        }
        body.addView(normal, LayoutParams(px(500), px(104)).apply { leftMargin = px(50 + 114); topMargin = px(50) })

        live.orientation = LinearLayout.VERTICAL
        live.gravity = Gravity.CENTER_VERTICAL
        val ph = LinearLayout(ctx)
        ph.orientation = LinearLayout.HORIZONTAL
        ph.gravity = Gravity.CENTER_VERTICAL
        ph.addView(LiveDot(ctx), LinearLayout.LayoutParams(px(22), px(22)).apply { rightMargin = px(6) })
        phase = Ui.text(ctx, "LIVE", 15f, k, 0xFFFF5A50.toInt(), Ui.medium)
        phase.letterSpacing = 0.13f
        ph.addView(phase)
        live.addView(ph)
        val sc = LinearLayout(ctx)
        sc.orientation = LinearLayout.HORIZONTAL
        sc.gravity = Gravity.BOTTOM
        sc.addView(Ui.text(ctx, "HSV", 26f, k, Color.WHITE, bold))
        score = Ui.text(ctx, "0 : 0", 32f, k, Color.WHITE, bold)
        score.letterSpacing = 0.06f
        sc.addView(score, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14) })
        oppLive = Ui.text(ctx, "", 24f, k, 0xE6FFFFFF.toInt(), Ui.medium)
        oppLive.maxLines = 1
        sc.addView(oppLive, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(14) })
        live.addView(sc, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2) })
        last = Ui.text(ctx, "", 17f, k, 0xBFFFFFFF.toInt())
        live.addView(last, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2) })
        live.visibility = View.GONE
        body.addView(live, LayoutParams(px(500), px(104)).apply { leftMargin = px(50 + 114); topMargin = px(50) })

        goalFlash.background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 380f * k
            colors = intArrayOf(0xE64696FF.toInt(), 0x334696FF, 0x004696FF)
            cornerRadius = 24f * k
        }
        goalFlash.alpha = 0f
        body.addView(goalFlash, LayoutParams(px(640), px(104)).apply { leftMargin = px(50); topMargin = px(50) })
        goalText.orientation = LinearLayout.HORIZONTAL
        goalText.gravity = Gravity.CENTER
        val tor = Ui.text(ctx, "TOR!", 52f, k, Color.WHITE, bold)
        tor.letterSpacing = 0.06f
        tor.setShadowLayer(24f * k, 0f, 0f, 0xE64696FF.toInt())
        goalText.addView(tor)
        goalScore = Ui.text(ctx, "", 34f, k, Color.WHITE, bold)
        goalText.addView(goalScore, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(22) })
        goalText.alpha = 0f
        body.addView(goalText, LayoutParams(px(640), px(104)).apply { leftMargin = px(50); topMargin = px(50) })

        alpha = 0f
        visibility = View.INVISIBLE
    }

    private fun color(glow: Int): Int = when (glow) {
        Hsv.VIOLET -> Color.rgb(150, 110, 255)
        Hsv.RED -> Color.rgb(235, 60, 70)
        else -> Color.rgb(70, 150, 255)
    }

    private fun ring(glow: Int) {
        val c = color(glow)
        if (comet != null && c == cometColor) return
        comet?.let { body.removeView(it) }
        cometColor = c
        val r = CometRing(context, k, c, 24f)
        body.addView(r, 1, LayoutParams(px(640 + 2 * CometRing.PAD), px(104 + 2 * CometRing.PAD)).apply {
            leftMargin = px(50 - CometRing.PAD)
            topMargin = px(50 - CometRing.PAD)
        })
        comet = r
        if (showing) r.start()
    }

    fun set(i: Hsv.Info) {
        title.text = i.title
        opp.text = "Gegner: " + i.opp
        line3.text = i.line3
        normal.visibility = View.VISIBLE
        live.visibility = View.GONE
        ring(i.glow)
    }

    /** Live-Stand; lastHsv = letztes Tor fuer den HSV (null = noch keins). */
    fun setLive(i: Hsv.Info, hsvGoals: Int, oppGoals: Int, lastHsv: Boolean?) {
        normal.visibility = View.GONE
        live.visibility = View.VISIBLE
        oppLive.text = i.opp
        score.text = "$hsvGoals : $oppGoals"
        goalScore.text = "HSV $hsvGoals : $oppGoals"
        val time = java.text.SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(i.match?.kick ?: 0L))
        last.text = when (lastHsv) {
            null -> "Anstoß war um $time Uhr"
            true -> "Zuletzt: Tor für den HSV"
            false -> "Zuletzt: Tor für " + i.opp
        }
        ring(Hsv.BLUE)
    }

    /** Tor fuer den HSV: Aufblitzen, "TOR!" springt auf, die Karte wackelt. */
    fun goal() {
        if (!Ui.anims) return
        live.animate().alpha(0f).setDuration(200).start()
        goalFlash.alpha = 0f
        goalFlash.animate().alpha(0.85f).setDuration(430).withEndAction {
            goalFlash.animate().alpha(0f).setDuration(3100).start()
        }.start()
        goalText.alpha = 0f
        goalText.scaleX = 0.6f
        goalText.scaleY = 0.6f
        goalText.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(PathInterpolator(0.2f, 1.6f, 0.3f, 1f)).start()
        val shake = android.animation.ObjectAnimator.ofFloat(body, View.TRANSLATION_X, 0f, -6f * k, 6f * k, -4f * k, 3f * k, 0f)
        shake.duration = 600
        shake.startDelay = 120
        shake.start()
        postDelayed({
            goalText.animate().alpha(0f).setDuration(300).start()
            live.animate().alpha(1f).setDuration(300).start()
        }, 3800)
    }

    fun show() {
        anim?.cancel()
        showing = true
        visibility = View.VISIBLE
        comet?.start()
        pivotX = width / 2f
        pivotY = px(50).toFloat()
        scaleX = 1f
        scaleY = 1f
        if (!Ui.anims) {
            alpha = 1f
            translationY = 0f
            return
        }
        translationY = -200f * k
        alpha = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 650
        a.interpolator = springIn
        a.addUpdateListener {
            val p = it.animatedValue as Float
            translationY = -200f * k * (1f - p)
            alpha = Math.min(1f, it.animatedFraction * 2f)
        }
        a.start()
        anim = a
    }

    fun hide(animate: Boolean = true) {
        anim?.cancel()
        showing = false
        if (!animate || !Ui.anims) {
            alpha = 0f
            visibility = View.INVISIBLE
            comet?.stop()
            return
        }
        pivotX = width / 2f
        pivotY = px(50).toFloat()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 620
        a.addUpdateListener {
            val f = it.animatedFraction
            if (f < 0.28f) {
                val e = easeOut.getInterpolation(f / 0.28f)
                translationY = 5f * k * e
                scaleX = 1f - 0.03f * e
                scaleY = 1f - 0.10f * e
                alpha = 1f
            } else {
                val e = easeIn.getInterpolation((f - 0.28f) / 0.72f)
                translationY = 5f * k + (-205f * k) * e
                scaleX = 0.97f - 0.05f * e
                scaleY = 0.90f + 0.02f * e
                alpha = 1f - e
            }
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!showing) {
                    visibility = View.INVISIBLE
                    comet?.stop()
                }
            }
        })
        a.start()
        anim = a
    }
}
